# Lumen Profile 編譯加速研究計劃

日期：2026-10-01  
狀態：CR2 已取得候選完成／暖快取結果及正式快取診斷；CR3 direct-temporal 已通過隔離編譯與靜態獨立審查。2026-10-03：CR2 已接入[明確 opt-in 的開發版 runtime](SHADER_CANDIDATE_RUNTIME.md)，預設仍為原版；原版主光照仍缺完成 baseline，候選畫質／FPS／Mac 與接入獨立審查待完成。以下各輪結果保留當時的驗證範圍。

## 目標與範圍

縮短 TOTEM_LUMEN profile 從開始準備到主光照與反射都可用的時間，同時維持目前畫質、穩定幀時間與平台支援。分別處理首次編譯與重複啟動；預先編譯、背景執行與真正縮短編譯時間分開報告。

只修改 TotemLumen。保留既有尚未提交的 LP1 生命週期修正。本研究不包含發布、改版本、改 OpenGL backend 或新增玩家必要依賴。Linux／RTX 先執行；Apple Silicon／MoltenVK 由使用者之後在 Mac 驗證。

## 已有證據與待驗證假說

- Alpha 60 的 Apple M4 紀錄：主光照 driver pipeline 約 24.2 分鐘、反射約 5.3 分鐘；SPIR-V cache hit 不代表最終管線重用。這是歷史資料，不是本輪 baseline。
- 現有主光照與反射各為大型 compute shader，反射預編譯在主光照完成後才啟動。
- SPIR-V 與 VkPipelineCache 均已存在；現有管線日誌的 warm 只表示讀入快取檔。
- 先前 RTX runtime 尚未完成全光照就緒，不能用 vanilla fallback 畫面或 submit-to-complete 數字宣稱 Lumen FPS 通過。
- 本輪以原始碼與 compiler 實驗測試「單一編譯單元與內聯／控制流程複雜度造成 driver stall」假說，尚未證明根因。

Iris 啟發與適用邊界：

| 觀察 | Lumen 實驗 | 不可直接推論 |
| --- | --- | --- |
| Iris 仍呼叫 OpenGL compile/link | 分離前端、driver 與快取量測 | 不是免編譯，也不代表 OpenGL 必然比較快 |
| Complementary 重用深度、法線、材質 | 保存並共用主視線命中資料 | 反射次級射線仍需要幾何追蹤 |
| 編譯前條件排除與 pass 開關 | 固定 profile 排除不可用分支 | 不能為縮短編譯關掉使用者啟用的效果 |
| 光照與歷史累積分開 | 評估 temporal/denoise 獨立 pass | 多 pass 不保證 FPS 提升 |
| Rethinking Voxels 部分模式跨幀重建 | 僅參考分工與資料重用 | 不採用減少像素／取樣作為本研究的無損優化 |
| 快取與管線重用 | 對照同源 shader 冷暖建立 | 快取檔存在不等於真正命中 |

## 不變條件

- Vulkan-only，compute voxel RT baseline；Apple Silicon 必須支援。
- 不降低 GI／陰影／反射取樣、反射次數、射線距離、解析度、精度或材質／流體／動態實體支援。
- 不靠 fast math、不同量化、降低歷史品質或畫面重建掩蓋差異。
- 不在 render thread 等待 driver 編譯；維持既有 device worker lease 與資源退役安全。
- 研究工具與輸出不成為玩家依賴，不把 SDK 或研究工具打入 release。
- 沒有對照證據的候選保留在研究路徑，預設 renderer 不切換。

## 執行順序

| 階段 | 工作與假說 | 產出與停止條件 |
| --- | --- | --- |
| CR0 基準 | 從正式 source builder 匯出 bootstrap、full、reflection；量測來源組裝、shaderc、driver pipeline，記錄 source/SPIR-V SHA-256、大小與工具／GPU 身分 | 可重現工具、逐步 START/COMPLETE、原始報告；過久任務以截尾下限記錄，不當成完成值 |
| CR1 快取與啟動 | 比較應用程式空快取、同 process 重建、跨 process 匯入；可用時取得 pipeline creation feedback；比較選單與進世界 | 區分快取讀入與真正命中；不刪玩家快取；未隔離 driver cache 時不得稱完全冷啟動 |
| CR2 有界 SPIR-V 整理 | 對相同 O0 輸出逐一實驗 dead function/dead branch/控制流程簡化；避免整套 exhaustive inlining／unroll | 每個候選獨立雜湊、驗證與 native timing；檔案變小不等於速度改善 |
| CR3 Profile 專用程式 | 在目前選定效果不變下，排除 debug 與確定不可達分支；限制 variant 數量 | 保留原版可選對照；定義切換設定的重編邊界；不能只保留較低畫質配置 |
| CR4 分階段與重用 | 先評估抽離 temporal/denoise，再評估 full/P16 共用 primary hit；每次只改一個邊界 | 中間資料 ABI、GPU 記憶體／同步成本、畫面與 per-pass timing；次級 trace 仍完整 |
| CR5 排程 | 比較 serial 與最多兩個獨立管線並行；主選單優先準備 | 比較全部效果 ready 與 base ready；量測 CPU／RAM／編譯期間卡頓；driver lock 可能抵銷收益 |
| CR6 採用 | 通過 Linux 與 Mac 畫質／效能／生命週期驗證後，才選擇候選進入正式路徑 | 原始證據、獨立 review、可回退設計與更新文件；未通過的候選不採用 |

本輪先完成 CR0 工具與初始結果。基準不足時持續診斷，不直接跳到大型 renderer 重構。

## 量測方法

### 編譯與快取

1. 匯出三份正式組裝 GLSL 與 O0 SPIR-V；保留原本名稱、main entry、Vulkan 1.2 shaderc target 與 compiler options。source builder 本身不變。
2. 純 compiler 量測不啟動世界，也不 dispatch shader。獨立 JVM 的 driver harness 使用相同 compute descriptor layout：set 0 binding 0 的 storage buffer。
3. 獨立 harness 的 GPU feature enablement 可能與 Minecraft 不同；只能定位 compiler 階段，不能替代遊戲 readiness、FPS 或畫面驗證。
4. 區分來源組裝、shaderc native 初始化、shaderc compile、shader module、pipeline create、cache save/read；每步失敗有非零退出碼。
5. 每筆記錄 source/SPIR-V SHA、Git HEAD 與 dirty 狀態、Java/LWJGL/shaderc 身分、OS、GPU、driver、pipelineCacheUUID、cache 狀態、wall time、RSS（可取得時）。
6. 應用程式空 VkPipelineCache 不等於 driver disk cache 冷。研究快取使用獨立目錄，不刪正常遊戲／driver cache。
7. 初輪探索每個大型 native compile 最多 120 秒；超時記為「大於觀測窗口」，不算 compiler error 或成功。只終止隔離的研究 process，不在仍編譯時銷毀共用遊戲 device。
8. 有完成候選才安排至少三次獨立 process 的匹配量測；保留首次與後續數值，不平均冷暖結果。shaderc JVM／native 初始化暖機單獨註明。
9. 選單與世界比較固定 shader、GPU／driver、cache 條件、顯示尺寸與 profile。歷史 24 分鐘數字僅為背景。

### 畫質與 FPS

- 使用相同 scene、相機、資源包、profile、內部解析度、時間／天氣與可控制的隨機種子；等待相同歷史收斂條件。
- 場景涵蓋不透明／alpha cutout、透明與染色透光、精確流體、粗糙／金屬 LabPBR、動態實體及反射。
- 對無語意變更先要求固定輸入數值／輸出一致；如 driver 產生浮點差異，列出差異與原因，不能自行放寬品質要求。
- 固定視角與移動路線做 A/B/A，各至少三個 60 秒視窗；報告 GPU frame time（可取得時）、CPU frame time、median/p95/p99 與卡頓。
- 只比較 full lighting 與 reflection 都 ready 的實際 Lumen frame。沒有 GPU timestamp 時清楚標明替代量測，不能當成 GPU-only 時間。
- 以重複 baseline 的波動估計量測誤差；任何可重現退步不採用，差異無法判定時增加樣本，不宣稱零成本。
- 驗證 resize、退出／重進世界、profile 切換、編譯中退出與 device 關閉；Mac 結果尚缺時標記 Linux-only，不宣稱完整平台通過。

## 驗證分工

研究 source set 與工具不修改 player renderer 時，執行研究工具編譯、既有 shader verifier、有效／無效輸入與 native 資源清理檢查。正式 renderer 一旦改動，執行相關 unit／shader／mixin gate、獨立 review 及上述 native 畫面／效能矩陣。既有成功測試只在輸入相同時沿用。

研究輸出存於 ignored build/reports/compile-research/。本文件保留結論與執行命令，原始 GLSL/SPIR-V／機器資訊不提交。固定研究工具可提交；本次不建立 commit 或發布。

## 執行紀錄

- 2026-10-01：完成計劃與範圍；保留現有 LP1 dirty changes。TotemWorkspace resolve/orchestration/context/allocation 均回報 Unknown Totem module: totem-lumen，故以本模組原始碼與 AGENTS 為準，未擴大至其他模組。
- CR0：研究工具、shader 匯出、bootstrap 冷暖應用程式快取對照與 full/reflection 有界 driver 探索已執行，詳見下方結果。尚無正式 renderer 編譯改善或畫質／FPS 通過結論。

## 參考資料

- [既有效能計劃](LUMEN_PROFILE_PERFORMANCE_PLAN.md)與[LP1 handoff](LUMEN_PROFILE_HANDOFF_2026-09-30.md)
- [Iris compile](https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/gl/shader/GlShader.java)、[pipeline reuse](https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/pipeline/PipelineManager.java)
- [Iris passes](https://shaders.properties/current/reference/programs/overview/)、[program options](https://shaders.properties/current/reference/shadersproperties/ordering/)
- [Complementary reflection](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/program/composite.glsl)
- [Rethinking Voxels lighting](https://github.com/gri573/rethinking-voxels/blob/main/shaders/program/composite_lighting_fsh.glsl)與[accumulation](https://github.com/gri573/rethinking-voxels/blob/main/shaders/program/composite_light_accum_fsh.glsl)
- [Vulkan pipeline feedback](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineCreationFeedbackFlagBits.html)
- [MoltenVK 1.4.2 cache implementation](https://github.com/KhronosGroup/MoltenVK/blob/v1.4.2/MoltenVK/MoltenVK/GPUObjects/MVKPipeline.mm)
- [SPIRV-Tools optimizer](https://github.com/KhronosGroup/SPIRV-Tools/blob/main/source/opt/optimizer.cpp)

外部來源的分支可變；以上為本次研究查閱位置，實驗數據以本機 shader 雜湊與執行報告為準。


## CR0 初輪執行結果

已完成獨立工具、正式 source 匯出、shaderc 編譯、bootstrap driver 快取對照與有界大型管線探索。這是初輪診斷，不是效能改善或正式 renderer 驗收。

### 可重現工具

- `src/research/java/dev/totem/lumen/vulkan/ShaderCompileResearch.java`：使用正式 bootstrap/full/reflection source builder，直接 O0 編譯，輸出 GLSL/SPIR-V、雜湊、階段耗時與結構計數。
- `src/research/java/dev/totem/lumen/vulkan/VulkanPipelineResearch.java`：隔離建立一條 compute pipeline，回報 driver feedback；不建立世界、surface 或提交 GPU dispatch。
- `scripts/run-compile-research.py`：記錄執行身分，限時監督研究 JVM。超時僅終止自己的 process group，保留 START 與截尾紀錄。
- Gradle `research` source set 與 `prepareCompileResearch`／`exportCompileResearch` 任務不加入一般 build/check 或玩家 JAR。已檢查現有 JAR 沒有兩個研究 class。
- 每次匯出保存 Git HEAD/dirty、實際 main/client/research compiled class tree 雜湊與載入的 shaderc native SHA。每次 native run 另存 run provenance，避免把較新的來源身分套到舊 shader。
- 匯入快取的輸出另存在該 run 的 `cache/`；已驗證原始 seed bytes 未被改寫。

### 執行命令

使用 Java 25、專案 Gradle 9.6.1。下列 `gradle` 代表已配置的 Gradle 執行檔；本機使用
`/home/thomas/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle`。
每輪使用新的 export/output 目錄；工具不覆寫既有結果。

```sh
gradle --offline exportCompileResearch verifyRuntimeShader \
  -PresearchOutput=build/reports/compile-research/my-export --no-daemon

python3 scripts/run-compile-research.py \
  --export build/reports/compile-research/my-export \
  --shader bootstrap --device 'RTX 5060 Ti' \
  --output build/reports/compile-research/my-bootstrap-empty \
  --seconds 120 --java "$JAVA_HOME/bin/java"

python3 scripts/run-compile-research.py \
  --export build/reports/compile-research/my-export \
  --shader bootstrap --device 'RTX 5060 Ti' \
  --output build/reports/compile-research/my-bootstrap-import \
  --cache-mode import \
  --cache build/reports/compile-research/my-bootstrap-empty/cache \
  --seconds 120 --java "$JAVA_HOME/bin/java"
```

將 `--shader` 換成 `full` 或 `reflection` 並使用新的 output，即可探索大型管線。
Mac 使用當機可辨識的 GPU 名稱；portability enumeration/subset 已按 extension 支援啟用，但工具本身尚未在 Mac 實測。

### 基準資料

環境：Linux 6.8.0-57、RTX 5060 Ti、Java 25.0.3+9、LWJGL 3.4.3+4。GPU driver raw version `2496987136`，pipelineCacheUUID `0465399043814824b440e3d1da10c207`。
HEAD `f4180149ca566ab44b91b7b1060505903c901ca8` 加既有 LP1 與本研究 dirty changes；精確身分見報告。
shaderc native SHA-256：
`67fd7a80737106374b739c97f56f5b6fbef62807011e6b7758886d231b1dce2a`。

以下是具完整 provenance 的 v2 單次 export，不是重複測試平均，也不是 compiler 效能排名。
同一 JVM 依序編譯 bootstrap/full/reflection，首次 shaderc compile 的初始化／暖機效應與後兩者不同。

| Shader | GLSL bytes | SPIR-V bytes | shaderc compile | functions | instructions |
| --- | ---: | ---: | ---: | ---: | ---: |
| bootstrap | 6,815 | 19,308 | 726.079 ms | 9 | 1,206 |
| full | 136,357 | 314,192 | 242.293 ms | 138 | 19,381 |
| reflection | 113,896 | 259,824 | 258.175 ms | 114 | 16,073 |

先前初次 export 的 full/reflection 分別為 62.289/50.043 ms；兩輪三份 GLSL 與 SPIR-V SHA 完全一致。差異不是 shader 優化，顯示單次計時不能拿來宣稱 speedup。兩輪均未使用應用程式 SPIR-V cache。

| Native probe | 觀測 | 可支持的結論 |
| --- | --- | --- |
| bootstrap empty application cache | pipeline create 56.630 ms；feedback valid、applicationCacheHit=false | 此次未由傳入的應用程式快取命中；不代表 driver disk cache 全冷 |
| bootstrap imported cache，獨立 JVM | pipeline create 4.558 ms；feedback valid、applicationCacheHit=true | 此 seed 確實可跨 process 重用；只有單對樣本，不外推到 full |
| full empty application cache | process 120.236 s 超時；PIPELINE START 已觀測，無 COMPLETE/result.json | 無世界／dispatch 時仍在 driver 建立階段等待；不是完成耗時 120 秒 |
| reflection empty application cache | process 120.207 s 超時；PIPELINE START 已觀測，無 COMPLETE/result.json | 與 full 相同，保留截尾觀測；不能宣稱編譯失敗或已完成 |

原始證據：
- `build/reports/compile-research/cr0-export-v2-20261001/manifest.json` 與三份 GLSL/SPIR-V。
- `cr0-bootstrap-empty-v2/`、`cr0-bootstrap-import-v2/` 的 started/result/provenance/runner-result。
- `cr0-full-empty/`、`cr0-reflection-empty/` 的原始 process log 與截尾／結果。
- 初次 bootstrap probe 在 extension enumeration 發生 MemoryStack 容量不足，未到 PIPELINE START；已改成有界 heap allocation。此失敗保留於 `cr0-bootstrap-empty/`，不計入 shader／driver 編譯比較。

### 靜態結構觀察

從 v2 SPIR-V 的 entry point 沿 OpFunctionCall 檢查，三份輸出的不可達函式數皆為零。因此「只刪未使用函式」目前沒有明顯可移除對象；仍需分開檢查可簡化的分支與 block，不能推論全部 DCE 都無效。

探索性 call-tree 計數假設每個靜態函式呼叫都展開、計入所有分支且不做迴圈展開，full 約 108.8 萬、reflection 約 36.7 萬個函式內指令。這只是複雜度假說的線索，**不是 driver 實際內聯結果、不是 GPU 指令數，也不是每幀執行次數**。最大的函式包含 p14IntersectVoxelGeometry、static/entity trace 與 p18ResolveSurface。

### 驗證與下一步

- 研究 Java 編譯與匯出通過；既有 verifyRuntimeShader 三份正式 shader 均零 warnings/errors。
- bootstrap 空／匯入快取兩個 native process 正常退出；seed SHA 不變；回饋 VALID/HIT 解讀已實測。
- 非法時間窗口與缺少 import seed 會在建立輸出／啟動 native process 前拒絕。
- 獨立 reviewer 的 export provenance 與 seed 可重現性兩項問題已修正並複審，無剩餘阻擋問題。
- `git diff --check` 通過；沒有改動正式 shader、renderer 算法、玩家依賴、版號或發布。
- TotemWorkspace impact 無法辨識此模組；test_plan 錯誤展開到其他模組的 Observer 等檢查。這不構成實際跨模組影響，本輪依 Lumen local instructions 與實際變更驗證。
- CR1 尚缺 full/reflection 完整完成與跨 process warm 對照、相同條件的選單／世界比較。初輪截尾不取代完整 baseline。
- 下一個有界實驗先定位 driver 的函式展開／控制流程負擔，評估選定 profile 的可排除分支；不要優先做只有數十至數百毫秒收益的 SPIR-V 打包。
- 任何 CR2/CR3/CR4 候選採用仍需完整畫面、GPU frame-time 與生命週期對照。Mac、正式遊戲 FPS、full quality readiness 與 LP1 編譯中正常退出皆尚未因本研究工具而驗證。

## Caustica 原始碼對照（2026-10-01）

研究版本固定為 [Caustica `330acd2d743bb6b2e27b53a4adb4a3c852b9e141`](https://github.com/ComfyFluffy/Caustica/tree/330acd2d743bb6b2e27b53a4adb4a3c852b9e141)。本輪是原始碼比較，沒有執行 Caustica，也沒有它的冷／暖編譯、同場景畫質或 FPS 樣本。

### 已確認差異

| 面向 | Caustica | Lumen 現況與研究含義 |
| --- | --- | --- |
| 求交後端 | Vulkan acceleration structures + 硬體 TraceRay；要求 RT extensions/features | compute shader 內做體素 traversal、幾何求交與 entity trace。這會帶入較大的軟體追蹤程式；是否造成 driver 編譯膨脹仍須實驗 |
| 前端編譯 | Gradle 執行 slangc、spirv-val，將各 stage SPIR-V 包入資源 | runtime source builder + shaderc O0／磁碟 SPIR-V cache。預編譯符合發布方向，但本機 full/reflection 前端只有約 0.05–0.26 秒，不能據此解釋 native pipeline 的長等待 |
| pass 分工 | primary 建立導引資料與 continuation queue，indirect 執行後續光路 | full 含追蹤／光照／temporal 等工作，reflection 是另一個大型 compute shader；可研究分工與狀態存活範圍 |
| 原生 pipeline 邊界 | primary／indirect 是同一條 RT pipeline 的不同 raygen SBT record，分兩次 dispatch | 不能把兩個 shader 檔案直接解讀成 driver 分別編譯、因此一定較快 |
| 路徑分支 | 反射／折射支線形成資料記錄，交由共用 path tracer 處理 | 可研究多個大型 trace 呼叫點／分支如何形成編譯負擔；Lumen 已有迭代 bounce loop，單純改成迴圈不是新方案 |
| 降噪與輸出 | noisy radiance + guides 交給 NVIDIA DLSS Ray Reconstruction，可搭配較低內部解析度；另有 frame generation | 與 Lumen 自有 temporal／denoise 的工作量、品質和平台不同。比較時必須分開內部解析度、原生 frame time 與插幀顯示 FPS |

### 必須保留的限定

- **沒有完全消除 primary retrace**：primary 的終端不透明命中／miss 會保留「求交前」continuation，indirect 再追一次。這是 payload 儲存量與重算之間的取捨，不是可直接照搬的零重追方案。
- **48-byte continuation 有量化**：方向用 octahedral unorm16，throughput／extinction 用 RGB9E5，IOR／ray cone 用 half。若移植概念，先做 float32 狀態版本；不能宣稱複製其 packed 格式完全不影響品質。
- 檢查到的 world RT 建立呼叫對 application `VkPipelineCache` 傳入 `VK_NULL_HANDLE`；driver 自有快取仍可能存在。没有證據把它的編譯體感歸因於應用程式 pipeline cache。
- 硬體 RT 將 traversal 交給硬體／驅動，有機會降低 shader 軟體求交複雜度，但另有加速結構建置成本。不能在沒有同場景資料時保證編譯或 FPS 改善。
- Lumen 必須保留 Vulkan compute／Apple Silicon 路徑；硬體 RT 只能作為未來 feature-detected backend。DLSS 不成為必要依賴。
- optional SER shader 有意在 reorder 後重建 payload，減少跨越該位置的存活資料。可借鏡縮短狀態生命範圍，但不能將 SER 本身視為跨平台解法。

### 對 CR2／CR4 的實驗增補

1. **CR2：共用追蹤流程與狀態範圍。** 從現有 exact-source／SPIR-V baseline 找出大型 trace 呼叫點，建立最小的研究候選，觀察將分支以狀態資料交给共用流程是否降低 driver 建立時間。先保持採樣數、bounce、RNG 序列、求交規則與浮點計算順序；對無法維持的部分明確記錄，不能自稱等價。
2. **CR4：導引、光照與 temporal 邊界。** 分開測試「儲存完整 primary hit」與「輕量 continuation 後重追」的成本。候選記錄新增 buffer bytes、pass／barrier、GPU timestamps，以及每條 pipeline 和全部就緒的時間；compile 變快但每幀變慢的候選不採用。
3. 第一個候選使用完整精度，不同時加入 packed state、較低 render scale、DLSS、減少 bounce 或採樣。避免無法判定收益來源，也避免把降質誤認為編譯優化。
4. 使用同一批場景與設定驗證 image difference、反射／透明／entity 邊界、temporal 穩定性、GPU frame-time／FPS 與資源生命週期。Linux 通過後保留 Mac 驗證點，由使用者後續在 Mac 執行。
5. 原有 CR1 完整完成與暖快取 baseline 仍待取得；Caustica source review 不取代 baseline，也不表示已找到能無損加速的方案。

### 原始碼依據

- [建置與 Slang／SPIR-V 資源打包](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/build.gradle)
- [RT feature 檢查](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java)
- [共用 RT pipeline／cache 參數](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java)
- [primary pass](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/primary.rgen.slang)、[continuation 格式／重追說明](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/segment.slang)、[indirect pass](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/indirect.rgen.slang)
- [SER 狀態生命範圍](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/trace_ser.slang)
- [兩階段 dispatch／RR 串接](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java)、[DLSS RR 導引與解析度](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java)

## CR2 第一個候選結果（2026-10-02）

**研究候選，尚未採用至正式 renderer。** 反射取得一對完整 native timing；主光照仍缺完整完成與對照。沒有 GPU dispatch、畫質或 FPS 驗收。

### 候選與語意範圍

shared-filtered-trace 將 p15TraceFiltered 的一般層與 terminal 層放入同一迴圈，共用一個 traceRayLimited 呼叫點；原本迴圈外的 terminal 呼叫移除。迴圈上限由 layer < 8u 改成 layer <= 8u，在求交後先判斷 layer >= maxLayers，因此仍保留第九次 terminal trace。

- 普通層的材質、流體、透光、步進與提早返回程式逐字保留；沒有降低取樣、反射次數、精度或距離。
- terminal hit 仍加 traveled，terminal miss 仍保留原始 distance；不得和普通層 miss 的行為混淆。
- 新增 src/research/java/dev/totem/lumen/vulkan/FilteredTraceResearch.java，僅供研究 exporter 使用；正式 source builder 與 renderer 沒有切換到候選。
- 來源錨點、clamp、呼叫數不符合預期時拒絕轉換。錨點檢查不能取代未來原始碼改動後的語意審查。
- exporter 預設 baseline；候選保存原始 source 副本與 SHA。未知 variant 在建立輸出目錄前拒絕。
- 獨立 reviewer 未發現此候選控制流程問題；另指出初版 assembleNs 包含轉換與 artifact I/O。已改成 assembly／transform 獨立計時，hash／I/O 不計入，並複審通過。初版 assembly 數據不使用；shaderc/native timing 不受此問題影響。

### 編譯與 native 觀測

同 CR0 的 RTX 5060 Ti／595.84、Java 25.0.3+9、LWJGL 3.4.3；native harness 功能與 Minecraft device 仍非完整等同。空 application cache 不代表 driver 全冷，driver 內部 cache 未控制。

| 探測 | 結果 | 限定 |
| --- | ---: | --- |
| 原版 reflection，empty application cache | 275.085 秒 pipeline create | feedback valid，applicationCacheHit=false；300 秒窗口內完成 |
| 候選 reflection，empty application cache | 108.194 秒 pipeline create | feedback valid，applicationCacheHit=false；120 秒窗口內完成 |
| 候選 full，empty application cache | process 120.363 秒截止 | 有 START、無 COMPLETE；不是 pipeline 完成耗時 |
| 原版 reflection，三個獨立 JVM 匯入同一 seed | 46.443／41.226／56.167 ms | 三次 feedback valid 且 applicationCacheHit=true |
| 候選 reflection，三個獨立 JVM 匯入同一 seed | 51.787／24.381／28.477 ms | 三次 feedback valid 且 applicationCacheHit=true |

反射這一對 empty-cache 觀測約縮短 **60.7%**。候選先測、原版後測，兩者只各一個完整樣本；**不是重複試驗平均，不是整個 profile ready 加速比例，也不是 FPS 改善**。尚需至少三組匹配的首次建立觀測，且交代 driver 內部 cache 條件；三組匯入快取樣本不能當成三組首次編譯樣本。

暖快取資料支持此 harness 能跨 process 重用兩份 reflection pipeline。原版與候選時間範圍重疊，這裡不做暖快取速度優劣結論；玩家實際啟動還包含其他管線與初始化。

| Shader | 原版 SPIR-V bytes | 候選 bytes | 原版／候選 OpFunctionCall 數 |
| --- | ---: | ---: | ---: |
| full | 314,192 | 313,952 | 378／377 |
| reflection | 259,824 | 259,584 | 281／280 |

兩份候選都只少 240 bytes、15 個 SPIR-V 指令；反射的明顯 native 時間差支持繼續研究呼叫點與控制流程複雜度，**尚未直接觀測 driver 的實際內聯策略，不能據此宣稱根因已證明**。

### 身分與驗證

- 計時修正後重新匯出的候選 GLSL／SPIR-V SHA 與初版完全一致；預設 baseline 的三份 GLSL／SPIR-V SHA 均與 CR0 v2 完全一致。bootstrap 未套用候選。
- full 候選 SPIR-V SHA：16a69fd9e703c7584799fa094cfdc3162d5eae35f2c7260bf789d337b83857b3。
- reflection 候選 SPIR-V SHA：8cd54780d960a7c93f123f00cbb2c19c68acfba0ab3adc1d09276781754ce524。
- 研究 Java build／candidate shaderc 與既有三份正式 shader verifier 通過，shader 零 warnings/errors。LWJGL/JDK native-access 提示另外保留於 log。
- 獨立 reviewer 回報 9,750 個抽象控制流程案例零差異，涵蓋層數 1–8、water／voxel 前綴、ordinary／terminal miss、entity／opaque、異常 advance 與 early exit；3/3 刻意錯誤控制被抓到。這是審查者 inline Python 模型證據（binary64、腳本化幾何、共用普通層 body），未保存為 repo 測試，**不代表實際 GLSL/GPU 數值、畫質或 FPS 驗證**。
- 原版與候選 seed 經三次匯入後 SHA 均未改變；匯入輸出保存在各 run 的研究目錄。
- 原版 reflection、候選 reflection 與六次暖快取程序正常結束；候選 full 由既有 supervisor 在截止時終止自身 process group。
- TotemWorkspace 仍無法辨識 totem-lumen；test_plan 錯誤回傳其他模組。依 Lumen 本地指示與實際 research-only 變更驗證，不擴大模組範圍。

### 重現與原始證據

    gradle --offline exportCompileResearch verifyRuntimeShader -PresearchVariant=shared-filtered-trace -PresearchOutput=build/reports/compile-research/new-cr2-export --no-daemon

省略 researchVariant 會匯出原版。其餘 native run 使用前述 scripts/run-compile-research.py，每次給新 output；反射原版完整對照本輪使用 --seconds 300，初輪候選使用 120。

原始報告均在 build/reports/compile-research/：
- cr2-shared-filtered-trace-20261001/：初版來源與 SPIR-V；assembly timing 作廢。
- cr2-shared-filtered-trace-v2-20261002/、cr2-baseline-export-20261002/：修正計時後候選／預設 baseline 匯出。
- cr2-shared-reflection-empty/、cr2-baseline-reflection-empty-300/：完整反射對照與快取 seed。
- cr2-shared-full-empty/：主光照截尾記錄。
- cr2-{baseline,shared}-reflection-import-{1,2,3}/：六次獨立 JVM 快取命中證據。
- cr2-final-checks-20261002/：build／shader verifier／預設行為／非法 variant／native runner logs 與 summary。

### 下一個有界工作

1. 取得 full 原版與候選的完整 baseline，並補反射首次建立重複樣本；不以 120 秒截尾值計算 full speedup。
2. 檢查正式遊戲的 pipeline cache key、完成後保存與下次載入／feedback，確認研究 harness 的快取結果能在實際啟動重現。
3. 只有候選值得繼續時才接入可選 A/B 驗證路徑，執行固定場景 GPU 畫面、frame-time、資源生命週期對照；再由使用者在 Mac 驗證。
4. full 若仍是主要負擔，再研究更深層共用追蹤流程或 pass 拆分；保持本輪候選獨立，避免混入其他演算法變動。

## CR2 主光照長窗口與正式快取診斷（2026-10-02）

本輪完成主光照 900 秒窗口對照、成功候選的快取重用、正式快取原始碼／歷史日誌審查，以及建立階段的命中診斷。正式 shader 算法仍未切換到候選；畫質／FPS／Mac 驗收仍待完成。

### 主光照結果

在同一研究環境，依序執行原版、候選；不載入世界、不提交 GPU dispatch。每個首次建立程序最多 900 秒。

| 探測 | 結果 | 判讀 |
| --- | ---: | --- |
| 原版 full，empty application cache | process 900.425 秒截止 | 有 START、無 COMPLETE；不能當成完成時間 900 秒 |
| shared-filtered-trace full，empty application cache | pipeline create 636.252 秒 | process 637.931 秒；feedback valid，applicationCacheHit=false |
| 候選 full，三個獨立 JVM 匯入同一 seed | 67.788／69.520／68.660 ms | 三次均 feedback valid 且 applicationCacheHit=true |

候選首次完成主光照 native pipeline，約 10.6 分鐘；原版仍缺完整完成值，**本輪不計算 full 百分比改善**。兩份首次建立都只有一次長窗口樣本，driver 內部 cache 未隔離。不得把這些數字當成正式遊戲 profile ready、畫質或 FPS 結果。

候選 cache 為 800,774 bytes，SHA-256：
e136481ee4af3403408fe541cd25abfc8fbcd474f7fcd7aa329958b8778281c3。
匯入前後 seed SHA 相同。原版未完成，沒有 full seed，也沒有執行原版 full 暖快取樣本。

原始資料：
- build/reports/compile-research/cr2-baseline-full-empty-900/
- build/reports/compile-research/cr2-shared-full-empty-900/
- build/reports/compile-research/cr2-shared-full-import-{1,2,3}/

以上長窗口與三次匯入使用重建前的研究 bytecode；下述新診斷有獨立重建／驗證，不能混算成同一次實作驗收。

### 正式快取審查

- 入口為 VulkanComputeProgram.createPipelineHandles，bootstrap／full／reflection 使用同一個 VulkanPipelineCacheStore；綁定已準備好的 pipeline 不重新建立。
- 快取依 schema、vendor、device、driver raw version、pipelineCacheUUID 分檔。它是聚合多個 pipeline 的 driver blob，沒有 shader hash 並不是缺陷。
- 第一次使用時載入；成功建立後標記 dirty。沒有其他 active create 時保存，再把管線交回 caller；因此原本 native elapsed 日誌不包含載入、lock wait、序列化與檔案寫入。
- 關閉時保存 dirty cache；有 active create 時延後保存／銷毀。device worker lifetime gate 另行等待完整工作結束。
- 現有排程已可在選單／資源載入階段開始 bootstrap 與 full 預熱，reflection 在 full 完成後才接續。因此「移到世界載入前」不能取代 shader 結構優化或快取驗證。
- 獨立 review 提到超過 64 MiB 時略過保存、提早 shutdown 後的新工作可能 uncached、跨 JVM 同名暫存檔等條件性風險；目前證據沒有證明它們造成此次長編譯，本輪不擴大修改。

歷史證據限於此次讀取的 run/client-26.3/logs/2026-10-01-2.log.gz 與 -3.log.gz（debug 檔有重複記錄）：
- bootstrap 成功保存 21,979 bytes，第二次載入後 native 建立記為 7 ms。
- 隨後 full START，但所讀日誌没有 full COMPLETE／SAVED。
- -3 在 13:46:00 full START，13:54:27 記錄 cache shutdown deferred、active build=1。這是未完成觀測，不是完成耗時。
- 舊 SESSION HIT／sessionCache=warm 只表示提供了非空磁碟資料，沒有 per-pipeline 命中回饋；不能以此證明 full 已快取。

實際 production blob 的 header 與檔名 UUID 均為 0465399043814824b556d8c4350698c9；研究 UUID 為 0465399043814824b440e3d1da10c207。vendor／device／driver 相同，但 UUID 不同，原因尚未確認。兩者保持分離，沒有移植或改名研究快取至遊戲目錄。

### 新增建立階段診斷

- 新增 src/client/java/dev/totem/lumen/vulkan/VulkanPipelineCreationDiagnostics.java，renderer 與 native research probe 共用。
- 僅在已啟用 Vulkan 1.3 或 VK_EXT_pipeline_creation_feedback、單一 pipeline、原 pNext 為空時附加回饋；其他情況保留呼叫，記為 unknown，不修改既有 extension chain。
- 依 VALID bit 決定是否解讀 application-cache HIT 與 driver duration；false 不代表完全沒有 driver 內部或部分快取重用。
- stack 上的回饋只活到同步建立呼叫結束；finally 還原 pNext，研究 probe 額外斷言正常返回後已還原。
- 正式日誌改成 SESSION LOADED／EMPTY，並列 cacheSource、applicationCacheHit、feedbackRequested／Valid、native elapsed 與 driverDurationNs。cacheSource 表示 session 初始來源；empty session 後續仍可能累積資料並命中。
- 診斷只在 pipeline 建立階段執行，沒有加入每幀工作或修改 shader。原生依據：[回饋結構](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineCreationFeedbackCreateInfo.html)、[VALID／HIT 語意](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineCreationFeedbackFlagBits.html)。
- reviewer 指出共用 client helper 後，runtime provenance 也必須記錄其實際 bytecode。runner 已增加完整 client class SHA map，獨立於 export-time 身分；修改後複審無發現。

### 新診斷驗證

離線 exportCompileResearch、verifyRuntimeShader、jar 通過。預設三份正式 GLSL／SPIR-V SHA 與 CR0 完全相同。JAR 包含正式診斷 helper，排除 ShaderCompileResearch、VulkanPipelineResearch、FilteredTraceResearch；没有發布或改版號。

| 共用 helper 的 native 測試 | native 建立 | 回饋 |
| --- | ---: | --- |
| bootstrap empty cache | 66.756 ms | requested=true、valid=true、hit=false |
| bootstrap imported cache | 19.446 ms | requested=true、valid=true、hit=true |
| 原版 reflection imported cache | 35.904 ms | requested=true、valid=true、hit=true |
| 候選 full imported cache | 36.015 ms | requested=true、valid=true、hit=true |

這四筆驗證新 helper 的行為，**不拿來與舊 helper 的暖快取樣本計算速度提升**。皆通過 pNext 還原檢查，runtime provenance 的 helper／Result class 雜湊與實際 compiled bytes 相符；seed 未修改。

報告：build/reports/compile-research/cr2-diagnostics-checks-20261002/summary.json、build.log 與 cr2-diagnostics-{bootstrap-empty,bootstrap-import,reflection-import,full-shared-import}/。
尚未驗證正式 Minecraft device 上的回饋、unsupported／既有 pNext／batch／exception 分支的原生行為、Mac 或遊戲 FPS。靜態審查已確認那些分支的保留與清理邏輯，不能當成 runtime 覆蓋。

### 下一個較小的候選

新的唯讀分析修正了 context-insensitive call graph 的解讀：沒有證明 temporal 重複計算鄰居光照。temporalHistoryColor 只算一次目前樣本，鄰居來自舊 history。

generated main 只在模式 8／9 呼叫 temporalHistoryColor；模式 10／11 改走 giTemporalIndirectColor／giCompositeFromIndirect。currentTemporalSampleColor 的舊 GI 分支因此可能在這個呼叫脈絡不可達；若 scene 輸入穩定，下一個研究候選可先將此 direct-temporal 路徑專用化，保留 temporal body，以排除舊 GI 邊而不新增 pass、buffer 或量化邊界。

採取前需核對所有 callsite、模式分流與 scene/history/output 不重疊；保留原 seed、pack/unpack、reprojection、history rejection、透光順序和 Y inversion。需覆蓋模式 8–11、history 各狀態、邊界／miss／透明、取樣數與飽和色，再做原生畫面／history 和 frame-time 對照。此候選尚未實作，driver 是否已排除這些邊亦未知。真正 pass 拆分保留為較後階段，不能提早 pack direct-light subtotal 而改變捨入／裁切。

TotemWorkspace 本輪仍不辨識 totem-lumen，impact 無法生成，test_plan 錯配其他模組。驗證按本模組實際診斷變更與研究工具執行，沒有擴大到無關 Observer 契約。


## CR3 direct-temporal 專用化候選（2026-10-02）

本輪接續上一輪「下一個較小的候選」，新增獨立 `direct-temporal` 研究 variant；不與 `shared-filtered-trace` 合併，不修改正式 renderer 或既有 LP1 修正。

### 變更與語意邊界

- `DirectTemporalResearch` 只替換 full shader 的 `currentTemporalSampleColor` helper，移除其舊模式 10／11 GI 分支。main 的模式 10／11 本來就走 `giTemporalIndirectColor`／`giCompositeFromIndirect`；唯一的 `temporalHistoryColor` 呼叫只在模式 8／9。
- temporal history body、GI body、seed／pack／unpack、reprojection／rejection、透光順序、main、history writes 與 Y inversion 全部逐 byte 保留；不新增 pass 或中間量化。
- 來源完整 SHA-256 固定為 `4371aba28c49e28304a2d84f3d0b51e52ed94cfebc9ab1c24cf8935313c5438e`。任何來源變動均拒絕套用，需重新審查 callsite／scene ABI，而不是自行延伸這項專用化。
- 審查確認 scene 是同一個 VkBuffer 中互不重疊的 static/header、history0、history1、output ranges；header upload／inFlight gate 維持 dispatch 期間 mode 穩定。不是四個獨立 Vulkan buffers。
- exporter 預設仍是 baseline；此 variant 的 bootstrap／reflection 仍為 baseline。研究 class 不加入 player JAR。

### 編譯與獨立審查

Java 25／Gradle 9.6.1 離線 `exportCompileResearch` 通過；三份 shaderc 輸出零 warnings。bootstrap／reflection 的 GLSL／SPIR-V SHA 與原版完全一致，`full.baseline.comp` 與已審查原版完全相同。

| full SPIR-V 結構 | 原版 | direct-temporal |
| --- | ---: | ---: |
| bytes | 314,192 | 306,664 |
| instructions | 19,381 | 18,930 |
| functions | 138 | 135 |
| OpFunctionCall | 378 | 358 |

候選 GLSL SHA：`f7ed7349bf52708d9f404da962407451c9947e903441cf926f857cc7926c3433`。
候選 SPIR-V SHA：`15150056e663d42106a2a4155a997987a4b5bc214200ec57625d110bee368470`。
檔案／call count 減少不是 driver 編譯加速或 FPS 證據。

獨立 review 接受此隔離研究變更，未發現靜態阻擋問題，證據位於 `build/reports/compile-research/cr3-direct-temporal-review.txt`。實際 Java 檢查驗證匯出與候選一致，並拒絕 callsite mode 改動、sampleIndex 改動、額外來源 bytes 和重複套用；非法 variant 在建立目錄前拒絕。檢查程式與摘要保存在 `cr3-direct-temporal-checks/`。既有開發 JAR 排除研究 class；本輪未重建 JAR，此掃描不宣稱新產物驗證。

### 重現

    gradle --offline exportCompileResearch -PresearchVariant=direct-temporal -PresearchOutput=build/reports/compile-research/new-direct-export --no-daemon

原始匯出：`build/reports/compile-research/cr3-direct-temporal-20261002/`。
原生探測使用既有 runner、獨立 output／cache 與 120 秒初輪窗口，不載入 Minecraft 世界、不 dispatch shader；driver internal cache 未隔離。沙箱內探測找不到 RTX，未進入 PIPELINE START，保留在 `cr3-direct-full-empty-120/`，不計入 driver timing。主機探測另存 `cr3-direct-full-empty-native-120/`。

TotemWorkspace 仍無法辨識 `totem-lumen`；impact 回報無法辨識模組，帶明確 changed_modules 的 test_plan 沒有解析到模組、僅回傳一般 build/unit 類別。本輪依 Lumen 本地研究規則與實際修改驗證；錯配的全域模組清單不構成本輪跨模組範圍。

正式採用仍需要模式 8–11／history／邊界／miss／透明／取樣／飽和色 GPU 數值與畫面、A/B frame-time、生命週期與 Mac/MoltenVK 驗收；本輪不切換 production variant，也不提交、推送或發布。


### CR3 原生結果與接續決策

| RTX full probe | 結果 | 判讀 |
| --- | ---: | --- |
| direct-temporal，empty application cache，120 秒窗口 | process 120.655 秒截止 | START、無 COMPLETE，沒有完成時間／seed |
| 同一候選，新的獨立 JVM／empty application cache，900 秒窗口 | process 900.808 秒截止 | START、無 COMPLETE，沒有完成時間／seed |

兩次程序均由 runner 在窗口截止後終止自己的研究 process group，未操作遊戲 device。900 秒 run 之前已執行過同一候選的 120 秒探測，driver internal cache 未隔離，不能稱嚴格冷啟動。既無 seed，不執行暖快取樣本。

CR3 的 source／SPIR-V／靜態正確性檢查通過，但這輪**沒有證明 native 編譯改善**，不計算速度比例、不接入 production 或遊戲 A/B 路徑。不能把兩個截尾時間當成兩個完成耗時，也不能憑不同 variant 的單筆結果定量排名。

接續優先保留已取得完成與快取 seed 的 CR2 shared-filtered-trace 路徑，補匹配樣本／原版完整 baseline，或先研究更深層的追蹤 callsite／控制流程。暫不反覆延長 CR3 同源窗口，也不靠降低畫質解決編譯負擔。所有畫質／FPS／生命週期／Mac 驗收仍保留。

報告：`cr3-direct-full-empty-native-120/`、`cr3-direct-full-empty-native-900/` 的 provenance、started、process log、runner-result。CR3 編譯／防漂移／review 摘要：`cr3-direct-temporal-checks/summary.json`。
