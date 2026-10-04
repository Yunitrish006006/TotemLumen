#!/usr/bin/env python3
"""Run one isolated native compiler probe with a bounded process lifetime.

Prepare first: Gradle exportCompileResearch -PresearchOutput=.../export
No world is opened, no shader dispatched, and only research-owned caches are used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "build/reports/compile-research"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def owned_path(value):
    path = Path(value).resolve()
    path.relative_to(REPORTS.resolve())
    return path


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", required=True, type=Path)
    parser.add_argument("--shader", required=True, choices=["bootstrap", "full", "reflection"])
    parser.add_argument("--output", required=True, type=owned_path)
    parser.add_argument("--cache", type=owned_path)
    parser.add_argument("--cache-mode", choices=["empty", "import"], default="empty")
    parser.add_argument("--device", required=True, help="Unique GPU name substring; never silently select software Vulkan")
    parser.add_argument("--seconds", type=float, default=120)
    parser.add_argument("--shutdown-probe", choices=["none", "wait", "interrupt"], default="none",
                        help="Exercise the production worker gate during isolated native creation; no Minecraft shutdown claim")
    parser.add_argument("--java", default=str(Path(os.environ.get("JAVA_HOME", "/usr")) / "bin/java"))
    args = parser.parse_args()
    if not 0 < args.seconds <= 3600:
        parser.error("--seconds must be in (0, 3600]")
    if args.cache_mode == "import" and args.cache is None:
        parser.error("--cache is required for import")
    manifest_path = args.export / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    shader = (args.export / (args.shader + ".spv")).resolve()
    expected = manifest["shaders"][args.shader]["spirvSha256"]
    if sha(shader) != expected:
        raise ValueError("SPIR-V no longer matches export manifest")
    classpath_file = ROOT / "build/compile-research/classpath.txt"
    classpath = classpath_file.read_text().strip()
    args.output.mkdir(parents=True, exist_ok=False)
    cache = args.cache or args.output / "cache"
    command = [args.java, "-cp", classpath, "dev.totem.lumen.vulkan.VulkanPipelineResearch",
               str(shader), str(args.output), str(cache), args.cache_mode, args.device, args.shutdown_probe]
    provenance = {
        "gitHead": git("rev-parse", "HEAD"),
        "gitStatus": git("status", "--short"),
        "trackedDiffSha256": hashlib.sha256(subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT)).hexdigest(),
        "exportManifestSha256": sha(manifest_path),
        "runnerSha256": sha(Path(__file__)),
        "exportProvenance": manifest.get("exportProvenance", "unavailable"),
        "spirvSha256": expected,
        "researchSourceSha256": {
            str(p.relative_to(ROOT)): sha(p) for p in sorted((ROOT / "src/research").rglob("*.java"))
        },
        "researchClassSha256": {
            str(p.relative_to(ROOT)): sha(p) for p in sorted((ROOT / "build/classes/java/research").rglob("*.class"))
        },
        # The native probe also executes the renderer's diagnostics helper.
        # Record current compiled bytes, independently of an older export manifest.
        "clientClassSha256": {
            str(p.relative_to(ROOT)): sha(p) for p in sorted((ROOT / "build/classes/java/client").rglob("*.class"))
        },
        "workerLifetimeClassSha256": {
            str(p.relative_to(ROOT)): sha(p) for p in sorted(
                (ROOT / "build/classes/java/main/dev/totem/lumen/vulkan/resource").glob("VulkanWorkerLifetime*.class"))
        },
        "cacheFileClassSha256": {
            str(p.relative_to(ROOT)): sha(p) for p in sorted(
                (ROOT / "build/classes/java/main/dev/totem/lumen/vulkan").glob("PipelineCacheFiles*.class"))
        },
        "lwjglJarsSha256": {
            p.name: sha(p) for p in map(Path, classpath.split(os.pathsep))
            if p.is_file() and p.name.startswith("lwjgl")
        },
        "command": command,
        "timeoutSeconds": args.seconds,
        "scope": "compile only; no Minecraft device feature parity, image validation or FPS measurement",
        "cacheMode": args.cache_mode,
        "shutdownProbe": args.shutdown_probe,
        "driverInternalCache": "uncontrolled",
    }
    (args.output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    started = time.monotonic()
    status = "exited"
    with (args.output / "process.log").open("w") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=args.seconds)
        except subprocess.TimeoutExpired:
            # The entire standalone process owns this device. Never call vkDestroyDevice
            # while its native compiler is still running, and never target a game process.
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            code, status = 124, "process_timeout"
        except BaseException:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise
    result = {
        "status": status, "exitCode": code, "processWallSeconds": time.monotonic() - started,
        "pipelineStartObserved": (args.output / "started.json").exists(),
        "completedResultExists": (args.output / "result.json").exists(),
        "note": "Timeout is censored PROCESS wall time, not a completed pipeline duration or shader failure.",
    }
    if args.shutdown_probe != "none" and code == 0:
        native = json.loads((args.output / "result.json").read_text())
        gate_ok = (native.get("status") == "complete"
                   and native.get("shutdownProbe") == args.shutdown_probe
                   and native.get("vkResult") == 0
                   and native.get("deviceDestroyed") is True
                   and native.get("activeWorkersAtShutdown") == 1
                   and native.get("newWorkRejected") is True
                   and native.get("shutdownGateWaitObserved") is True
                   and native.get("workFinishedAtGateReturn") is True
                   and native.get("workerTerminatedBeforeCleanup") is True
                   and native.get("interruptRestored") == (args.shutdown_probe == "interrupt"))
        result["shutdownGatePassed"] = gate_ok
        if not gate_ok:
            code = 1
            result.update(status="shutdown_probe_failed", exitCode=code)
    (args.output / "runner-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    for line in (args.output / "process.log").read_text(errors="replace").splitlines():
        if line.startswith(("PIPELINE ", "Exception", "Caused by:")):
            print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
