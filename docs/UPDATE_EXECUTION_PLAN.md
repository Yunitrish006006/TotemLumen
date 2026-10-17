# Totem Lumen 更新計劃與執行表

建立日期：2026-10-09。範圍依使用者前文中的 Totem Lumen；先延續既有待辦，不另行假定 Minecraft 升級目標或新功能。使用者補充：依新的方式重新規劃，目前待辦由更低階模型執行。

## 持續 goal

使用者已於 2026-10-09 明確要求將整張 U0–U11 表設為 goal，開始逐項執行；goal 已建立且為 active。從 U2a-build 接續，沿用有效 U0/U1 證據，不重复驗證。每個微任務完成後留下 checkpoint，再由持續 goal 接下一項；單項停止邊界不再要求使用者逐次喊繼續。需要外部環境、核准或缺失證據時如實保留該 gate，優先進行不依賴它的項目。

有界執行代理採使用者指定的較低階模型配置 `gpt-6-luna / medium`；主線保留整合與契約判斷。Goal 不包含發布授權。

## 本輪基準

- 擁有者：TotemLumen；分支 `main`；開始時 HEAD `7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`，工作樹乾淨。
- 既有文件基準：Minecraft 26.3、Java 25、Alpha 61；shader 候選 `shared-filtered-trace` 保持 opt-in。
- 執行原則：一個主要概念一次處理；每項留下來源、驗證與下一項 checkpoint。變更 renderer 前重新取得對應 intelligence 約束、閱讀架構與階段文件。
- 實作、歷史驗證、本輪驗證、原生畫面驗收分別記錄。來源或材質 ABI 改變後，不沿用舊版編譯耗時當作新版結果。
- 預計耗時依完整 pipeline 的實測而定，不編造日期。下表順序是依賴順序，不是完成承諾。
- 分工：主線負責規劃、約束與整合；有界微任務交給可用的 `gpt-6-luna / medium`。目前模型不因規劃而切換；實際代理執行與驗證另記。若低階模型遇契約、安全或證據衝突，回交主線判斷。

## 更新計劃表

| ID | 順序／依賴 | 更新項目與主要概念 | 完成條件／focused validation | 狀態 |
| --- | --- | --- | --- | --- |
| U0 | 第一項 | 固定來源與 shader 對照基準 | 核對 HEAD、工具鏈、原版／候選 routing 與 tint packing；保留實際檢查結果 | 完成 |
| U1 | U0 | 候選接入獨立審查 | 分成 U1a routing／transform、U1b tint ABI、U1c lifetime；各自留獨立審查證據 | 三項靜態獨立審查完成；無 blocker；不代表 runtime 通過 |
| U2 | U0、U1 | 匹配原版／候選畫面基準 | 同一測試世界、鏡頭、時間、解析度、品質及 tint ABI；兩側 full/reflection READY 且實際 dispatch／WORLD TAKEOVER；保存來源 hash、log、畫面 | baseline 原鏡頭／水面證據已保存；primary coverage 契約、8-word metadata、post-lighting resolve、R1 deterministic residency、R2 lookup headroom 與 R3 resident-change invalidation 契約已完成；runtime 畫質、紅色強度與候選配對仍未驗收，U2c 未完成 |
| U3 | U2 | LP-1 原生生命週期 | 冷編譯中退出、暖 dispatch 中關閉、resize、反射 off/on、離開／重進世界；無 stale descriptor／closed buffer／Vulkan 錯誤，正常退出可追溯 | 待執行；已有部分歷史證據 |
| U4 | U2、U3 | P17 renderer family 覆蓋 | 掉落物（平面／方塊）、船／礦車、兩種 projectile；capture/GPU count、可見幾何與 despawn/rejoin 清理皆通過 | 待執行 |
| U5 | U2、U3 | P14E 流體畫面驗收 | 坡面、水瀑、waterlogged、live update、水透光／反射、熔岩發光逐項核對 | 待執行 |
| U6 | U2、U3 | P14D 方塊實體覆蓋 | 鐘／箱動畫、出 voxel 範圍與特殊 command family；缺少 adapter 的項目另拆單一概念實作 | 待執行 |
| U7 | U2、U3 | P18 材質與 tint 容量 | LabPBR reload、alpha cutout、材質反應、跨 biome registry 容量／fallback；剩餘 P18D 分項處理 | 待執行 |
| U8 | U3–U7 | LP-0 固定場景效能基準 | A/B/A、多次 frame-time／FPS、設定與 source hash、section lookup occupancy／hit/miss probe；不把 prewarm 當 FPS | 待執行 |
| U9 | U8 | 已量測熱點的一項優化 | 先依量測選 lookup 或 primary-hit reuse；拆獨立 ABI／實作任務，畫質及生命週期不退步，有匹配重測 | 待選題；不得先假定加速 |
| U10 | 對應 U2–U9 | Apple Silicon／MoltenVK 驗證 | 同源 Mac full/reflection readiness、畫質、resize/rejoin/close 與效能紀錄；需 Mac 執行來源 | 待外部環境；既有 handoff 指定由使用者驗證 |
| U11 | Client 基準穩定後 | GL4 server RGB profiling | 沿用 gameplay roadmap；取得實際世界 tick／backlog／spawn correctness，EULA load smoke 不代表通過 | 待執行 |

P19+、硬體 RT 與進階取樣保留在既有 roadmap；先完成目前場景與效能基準。發布是後續獨立階段，需確定版本與發布目標後才納入執行表。

## U0 執行規格

| 欄位 | 定義 |
| --- | --- |
| goal / primaryConcept | 建立目前來源可使用的 shader 對照基準 |
| readScope | AGENTS、README development、planning index、handoff、candidate runtime、build.gradle、相關 shader verifier 與既有報告 |
| writeScope | 此計劃、planning index；ignored build reports |
| focusedValidation | Java／Gradle JVM 25；現有 `verifyDefaultShaderVariant`、`verifyOptInShaderVariant`、`verifyBlockTint` |
| doneCriteria | 固定 HEAD；工具鏈確認；三個現有 verifier 通過；文件連結有效且 diff 無空白錯誤 |
| stopBoundary | 完成 checkpoint，下一微任務為 U1；本項不宣稱 renderer／FPS／Mac 驗收通過 |

選這三項檢查是因為歷史報告沒有本輪精確 HEAD 的證據紀錄；不為文件修改重跑全部 build 或原生編譯。

## 證據與 checkpoint

本輪 2026-10-09 驗證：Temurin 25.0.3、Gradle 9.6.1 launcher/daemon JVM 25；`--offline --no-daemon verifyDefaultShaderVariant verifyOptInShaderVariant verifyBlockTint` 實際成功（38 s，三项執行、五项 up-to-date）。精簡原始輸出保存在 ignored `build/reports/update-plan/u0-verification-summary.txt`。檢查涵蓋 golden source hash、routing／invalid-drift／restart boundary、tint identity／GPU pack／cube retention，不是 shaderc、原生編譯或畫面驗收。

U1a 已由實際啟動的 `gpt-6-luna / medium` 獨立完成，ignored 報告 `build/reports/update-plan/u1a-routing-review.md`，未發現 routing blocker。報告保留配置組合未全面覆蓋、GPU 語意與資源生命週期未驗證的界線。U1b/U1c 不因 U1a 通過而算完成。

U1b 同一低階代理獨立審查完成，ignored 報告 `build/reports/update-plan/u1b-tint-abi-review.md`，未發現 CPU/GPU tint ABI blocker。Cube 最高合法 surface-set ID 已有測試；mesh 最後合法 quad slot 不由目前 tint verifier 證明，列入 U7 容量驗證。這是靜態結論，不是新增 shader compilation 或 runtime 證據。

U1c 同一低階代理獨立審查完成，ignored 報告 `build/reports/update-plan/u1c-integration-lifetime-review.md`，未發現候選接入新增 ownership blocker。Selector／transform 在既有 worker lease 內執行，失敗經既有 prewarm gate 清理；靜態結論不證明 native GPU queue、shutdown 或 Mac 行為。

U0/U1 checkpoint：repository `TotemLumen`、branch `main`、HEAD 不變；正式來源檔案未修改，tracked 改動為 planning index、新建 execution plan；三項 verifier 成功、三份有界獨立審查完成；27 個本機文件連結皆存在，`git diff --check` 通過。剩餘風險：畫面等價、材質容量、原生 queue/close、效能與 Mac。下一微任務 U2a：固定並核對受控 runtime fixture。

U2a 可用性 checkpoint：低階代理完成唯讀盤點與 manifest，ignored `build/reports/update-plan/u2a-runtime-fixture.json`、`.md`。舊 candidate 測試目錄及世界存在，但可變狀態中的 weather/camera/quality 尚未固定，不能直接當作配對基準。現有 Gradle 9.6.1 launcher 可用（U0 已實測）；PATH／wrapper 缺少不是工具鏈阻擋。未啟動遊戲或改動 world/config/cache；baseline hash 尚待實際 source export；readiness/dispatch/takeover 皆未驗證。本項只有盤點完成，fixture 建立及 U2b/U2c 尚未完成。

下一微任務縮小為 U2a-build：只建立新的 ignored 測試副本與固定 manifest，不使用舊 run 可變狀態作證據；完整定義 camera/weather/quality 與同源 hash 後再開始 U2b 的 bounded baseline run。遵守 intelligence 回傳的 `stopAfterEachTask: true`，本輪在此 checkpoint 結束。這個停止邊界不撤回使用者對後续依表執行的授權，也不表示整張更新表已完成。

Goal 啟動後 U2a-build 已交付低階代理執行。主線從 U0 當次輸出取得 baseline full hash `d8ea2f1b1a4158db755a8348d7c22b9c24e051c24cb0d90bfbaf57cc9d8fac3b`、reflection hash `a409c6a67f91d1e4898b4892d8cd31986dc9453661967a9c1d0b6505e3a12e54`，並與既有 GLSL 匯出檔逐一 SHA-256 比對，baseline/candidate 四份皆一致。紀錄 `build/reports/update-plan/u2-source-hash-reuse.json`。此結果補足 U2a 的 source hash 缺口，不是新增 shaderc 或 native runtime 結果。

U2a-build checkpoint：低階代理已建立新隔離模板、baseline/candidate 兩份 run 與 init scripts，紀錄 `build/reports/update-plan/u2a-build.json`、`.md`。主線獨立複核兩份各 57 個檔案 SHA-256，皆與模板一致；白名單設定相同、應用程式快取初始空白、原世界／設定／快取未改。原版／候選 hash 均對應 U0 證據且兩組不同。第一次 review 發現候選 hash 誤填原版，已修正並由獨立斷言重驗；另從 26.3 本機 jar 核對 gamerule 正確名稱 `advance_time`、`advance_weather`，設定鍵／enum 由正式 RendererSettings 解析器核對。

Runtime controls 仍 pending：实际載入的 profile/quality、960×540 framebuffer、時間／天氣、玩家位置／yaw/pitch。`controlledMatchedRuntimeReady` 維持 false；未啟動遊戲。本次 focused validation 只證明隔離副本／manifest 的一致性與範圍，不能當作畫面或 FPS 結果。

下一微任務 U2b-setup：只啟動隔離 baseline client、進入測試副本，設定並記錄同一控制場景；完成 readiness/hash 日誌核對後，再做有上限的 U2b 原版呈現基準。不得在等待 native compiler 期間改 source 或混入候選結果。Goal 保持 active，外部 Mac gate 仍未完成。

U2b-setup checkpoint（2026-10-09）：原先代理升權工具呼叫被中斷，沒有建立遊戲 session。主線取得新的啟動核准後，在私有 Xvfb 中啟動 baseline；低階代理接手場景設定。重要環境差異：沙箱內 `ps` 看不到升權的主機程序，不能據此宣稱程序已停止；以 exec session 和主機程序證據追蹤，不重複啟動。

新原版 runtime 已確認 Minecraft 26.3、RTX 5060 Ti、NVIDIA 595.84 / Vulkan 1.4.329，實際設定與 U2a 目標相同。已進入 LumenCandidateTest，伺服器回應確認正午、`advance_time=false`、晴天、`advance_weather=false`、玩家 `(-2.5,-60,6.5)`、明確 teleport yaw/pitch `(0,0)`，並 `/tick freeze`。主線獨立核對日誌與 960×540 截圖；第一批輸入曾被當作聊天發送，修正開啟輸入框後的等待時序後才取得真正指令成功回應。

原版 full source hash 已由本次 log 確認；reflection hash 仍只是 U0 預期值，尚未觀察 runtime selection。setup 截圖顯示 full lighting 30% 編譯／vanilla fallback，只是場景設定證據。ignored 記錄 `build/reports/update-plan/u2b-setup.json`、`.md`；live session/display/window 等主機資訊只存 ignored 記錄，不寫入正式文件。

原版 full prewarm 開始於 2026-10-09 15:11:36 CST，900 秒觀察窗口到 15:26:36。編譯期間保持既有 worker/client，不因觀察窗口屆滿強制殺程序。兩份世界雖最初相同，baseline 在 freeze 前的 entities 已移動，後續須從控制 checkpoint 匹配動態狀態，不能僅憑同 seed／同鏡頭宣稱受控 A/B 已完成。正式來源與 build 在 client 啟動後沒有改寫。

U2b 有界窗口結果：15:27:37 CST（開始後 961.1 s）核對完整 log，仍無 full prewarm COMPLETE、reflection binding READY 或 WORLD TAKEOVER。記錄等待下限 `>=900s`，不是完成編譯時間；此窗口未取得原版呈現驗收。ignored `build/reports/update-plan/u2b-window-900.json` 保存觀察時刻與 log snapshot SHA-256。exec session 經 60 秒 wait 仍存活，沒有殺程序或重啟。後續自然完成只能另記為窗口外事件，不回填成此窗口通過。

下一微任務 U2-controlled-checkpoint：正常離開測試世界、確認 save 完成後，新建受控世界 checkpoint 和副本（保留原模板），核對 time/weather/gamerules/player pose 與 tick-freeze 的保存／恢復語意，供兩側使用。只操作隔離世界及 ignored 證據；不重建正在載入的正式 classes，不開第二個競爭 GPU compiler 的客戶端。此後再決定候選的啟動次序及原版窗口外結果如何記錄。

U2-controlled-checkpoint 結果：2026-10-09 15:50:04–15:50:06 正常登出、保存玩家／三個維度並回到標題畫面；主機程序證據確認 Server thread 已停止，原版 client 仍在。低階代理建立 `checkpoint-v2/LumenCandidateTest` 與 `staged-candidate-world/LumenCandidateTest`，保留原模板及尚未啟動的 candidate；主線獨立重算 58 個檔案 SHA-256，與保存後 baseline 完全一致（排除 session.lock／uid.dat）。證據為 ignored `build/reports/update-plan/u2-controlled-checkpoint.json`、`.md`。這證明副本一致，不證明重新載入後動態狀態一致；尚未逐欄解析 NBT 或實測 freeze 恢復。26.3 jar 中已檢查的 tick manager／level-data 路徑未見 freeze 序列化，下一次進入仍須重新執行並確認 `/tick freeze`。

U2b 窗口外自然完成事件：full prewarm 15:40:41 完成，耗時 1,744,755 ms；接著 full binding／renderer READY 並 WORLD TAKEOVER，內部輸出 643×362。reflection 15:45:57 完成，耗時 316,310 ms，binding 與 P16 READY。兩個來源 hash 均匹配 baseline；ignored `build/reports/update-plan/u2b-outside-window-completion.json` 保存事件與 log 指紋。900 秒窗口未完成的原結論保持不變。尚未取得無模糊 full＋reflection 畫面；prewarm 時間、診斷 effectiveTotemFps 與 submit-to-complete 都不是受控 A/B 實際遊戲 FPS。應用程式 cache 初始 MISS 也不保證 driver cache 為冷。

效能研究線索：此場景的 stable GPU lookup 為 128/128 slots、lookupMaxProbe=84。U8 要量測 occupancy／probe 與 frame-time 的關係，U9 再驗證針對性改善；此數字目前不構成效能瓶頸因果證明或提升 FPS 結果。

下一微任務 U2-load-controls：唯讀核對 26.3 測試世界載入時的 tick／function 執行順序，決定兩側可重現的 freeze 與玩家 pose 控制方式，補足重新載入的動態匹配缺口。只讀 checkpoint、既有控制證據與本機 jar；輸出 ignored 可行性紀錄，不改正式 source、不操作 GUI、不啟動另一 client。完成後接 U2b 的暖載入完整畫面擷取；原版與候選仍循序執行。

U2-load-controls checkpoint：低階代理已完成本機 26.3 bytecode 分析，主線獨立核對 `tickServer` 115→120、`tickChildren` 34→47→172 的呼叫次序及 TickRateManager setter／tick／player exemption。load function 可以在 level tick 前要求 freeze，但當次 runGameElements 已先計算，不能保證零 tick；玩家也不因 world freeze 而固定位置。ignored `build/reports/update-plan/u2-load-controls.md`、`.json` 明列早期 chunk preparation 與首次玩家可用性尚未 runtime 驗證。不安裝 datapack，避免把減少漂移冒充精確動態匹配。現有 client session 存活，主線已確認世界清單僅有 LumenCandidateTest，960×540；沒有啟動第二個 client。

下一微任務 U2b-warm-capture：從已確認的世界清單暖重進既有 baseline，重新凍結、設定及查詢玩家 pose，核對重進後 full/P16 readiness／takeover，取得無選單模糊的完整畫面。readScope 為隔離 baseline log、視窗及控制報告；writeScope 只有該測試副本和 ignored log／截圖。focused validation 為指令回應、重進事件與實際畫面；doneCriteria 為證據保存或明確 runtime failure；stopBoundary 為單次暖重進觀察（最多 120 秒），不以此宣稱動態配對、候選畫質或 FPS 已通過。後續配對需控制或核對動態物件；U4 動態 renderer family 驗收仍完整保留。

U2b-warm-capture checkpoint：2026-10-09 16:17:26 暖重進後 full renderer／P16 READY，16:17:27 WORLD TAKEOVER。主線執行低階代理準備並經審核的隔離 GUI 腳本，正常完成；16:18:01 Pos 讀回 `[-2.5,-60.0,6.5]`，16:18:03 Rotation `[0.0,0.0]`，16:18:04 tick query 確認 frozen，time/weather 控制成功、兩項 advance gamerule 已為 false。主線已實際檢視 `baseline/warm-rejoin-review.png`、`warm-full-review.png` 與聊天提示淡出後的 `warm-full-clean.png`（皆在 `/tmp/lumen-u2-controlled-20261009`）；乾淨畫面有草地／天空及獨立 HUD，無選單模糊。這是 full/P16 就緒後的實際平坦場景呈現證據，不能證明反射表面、複雜材質／流體／動態物件品質或 FPS。候選尚未啟動，U2 配對仍未完成；下一步先評估此鏡頭是否足以覆蓋候選變換，再建立有代表性的共同受控場景，而非只靠平坦畫面通過整個 U2。

U2-scene-build 微任務：在現有隔離 baseline 前方小範圍建立可重現的 stone 遮擋、cutout leaves、彩色玻璃／發光物及水面場景，固定鏡頭；既有 checkpoint 保留。readScope 為 26.3 本機 block registry、既有 GUI 控制腳本／log 和候選 trace 語意文件；writeScope 僅測試副本及 ignored 腳本／manifest／畫面。focused validation 為命令座標與 block ID 審查、實際回應、pose/freeze 讀回及畫面；doneCriteria 為可見代表性場景與保存證據，或具體失敗原因；stopBoundary 為一次建置／檢視，不改 renderer，不把靜止水面當 U5 完整流體驗收。然後正常保存為兩側共同 scene checkpoint；動態狀態仍須另核對，U4 保留。

U2-scene-build checkpoint／Discord 交接（2026-10-09）：代表性場景已建置並於 18:34:26 正常保存三個維度、回到標題。原始 18 條輸入後半因慢 frame 導致 chat 開啟時序不足，誤開 Advancements；不可把全序列報成成功。低階代理提供慢速 recovery，主線執行並核對 water fill 12 blocks、pose／rotation／freeze 回應。玩家仍受重力影響，最初 -59 掉到 -60；補單格平台 `(-3,-60,7)` 後實測 Pos `[-2.5,-59.0,7.5]`、Rotation `[0.0,10.0]`。主線已看 `baseline/representative-scene-platform.png`；獨立視覺審查 `build/reports/update-plan/u2-scene-visual-review.md` 指出玻璃嚴重斑點、紅色溢光及水面尚無足夠可辨識證據。建置／存檔完成，畫質未通過，U2 保持未完成。

最新共同場景備份為 `/tmp/lumen-u2-controlled-20261009/representative-checkpoint/LumenCandidateTest`，主線對保存後世界與副本逐檔 SHA-256 比對；完整證據在 ignored `build/reports/update-plan/u2-representative-checkpoint.json`。保留 template、checkpoint-v2 與舊 staged-candidate-world；後兩者尚無代表性場景，勿誤用。candidate 世界也尚未換成最新 checkpoint、尚未啟動。

使用者明確要求暫停此處、改由 Discord 接手。接手入口使用已設定的根 workspace（`/codex run workspace:workspace task:...`），先讀本表及 checkpoint 證據。所有正式來源仍為 HEAD `7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`；只有本表與 planning index 文件變動，沒有 commit/push/publish。既有 baseline client 在主標題，原 exec handle 20149（跨 surface 不保證可用）、host PID 3784051、私有 DISPLAY :121／window 2097199；接手必須重新查活躍程序／視窗，不能只因 handle 不可見就另開 GPU client。下一項：核對最新場景存檔、取得可辨識水面與乾淨代表性 baseline 畫面，處理／分類玻璃噪點與溢光；再正常關閉 baseline、從最新共同 checkpoint 循序啟動候選配對。U3–U11 原範圍及 Mac 外部 gate 全部保留；執行沿用低階模型有界工作與主線整合／獨立審查，不降低畫質來冒充 FPS 優化。

歷史候選 runtime、163 tests、tint 和 warm resize/close 結果仍只代表原文件列出的範圍，不自動升級整列狀態。此次正式程式碼未變更，未重跑完整 build；impact 顯示 touched/impacted 僅 TotemLumen、無共享契約。test_plan 的模組分類涵蓋後續 renderer/server 任務，不能冒充此次已執行的測試清單。

後續每項記錄：task ID、repositories touched、branch、HEAD、files changed、validation、remaining risks、next task。失敗時保持該項未完成，先處理具體阻擋原因。

### Discord 接手：U2b-representative-capture checkpoint（2026-10-09）

本次微任務 goal／primaryConcept 是沿用既有 client 補齊代表性原版畫面。readScope：上述共同場景 manifest、baseline 日誌與既有 screenshot、主機程序／視窗識別；writeScope：ignored 稽核報告及本表。focusedValidation：58 檔 SHA-256 與路徑集合、主機 PID／DISPLAY、實際畫面判讀。doneCriteria：保存可辨識水面及乾淨畫面，或具體 runtime 阻擋證據；stopBoundary：本項 checkpoint，不啟動候選、不延伸生命週期／優化實作。

- 主機升權唯讀核對：PID `3784051` 已不存在，`DISPLAY :121` 無法開啟，不能沿用原視窗暖重進。可辨識的其他 Java 為 Observer servers／Gradle；root PID `1615103` 的 cwd／exe／environment 無權讀取，角色尚未確認。空白的 GPU compute-process 清單不能證明沒有 graphics client。本項未啟動任何新 client。
- 實際委派模型為本 session 可用的 `gpt-5.6-luna / medium`，任務 `u2_fixture_audit`；不是上文歷史配置的 `gpt-6-luna`。代理核對存檔與日誌，主線另重算 baseline 與 representative-checkpoint 各 58 檔，兩側所有 SHA-256 及路徑集合均符合原 manifest（排除 session.lock／uid.dat）。報告：`build/reports/update-plan/u2-resume-fixture-audit.json`、`u2-resume-runtime-gate.json`。
- 日誌只證明 18:34:26 整合式伺服器保存／停止，不能證明 client／JVM／Vulkan 正常關閉；client 消失原因仍未知，不能列為 U3 close 通過。主線檢視 `representative-scene-platform.png`：中央玻璃明顯噪點、紅色溢光仍在，水面無法可靠辨識，聊天提示仍可見；不能代替乾淨畫面或候選對照。影像 SHA-256 與檢視限制已記錄。
- repository：`TotemLumen`；branch：`main`；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。本次 filesChanged：本表及上述 ignored reports；既有 `docs/PLANNING_INDEX.md` 改動保留。正式 source／world／config 未改。實際 configured versions 為 Minecraft 26.3、Java 25、Loader 0.19.5、Fabric API 0.160.5+26.3、Loom 1.17.12、modern-yarn 26.3+build.1；context snapshot 的旧版本／commit 不取代本機證據。
- impact：僅 TotemLumen，無共享契約；test_plan 模組分類保留作後續 gate。本項只做 fixture／程序／文件驗證，沒有重跑 Gradle、shader、native 或 Mac suites，也未新增 runtime READY／FPS 證據。U0/U1 有效證據沿用；U2 保持未完成，U3–U11（含畫質、生命週期、FPS 研究、Mac 外部 gate、server RGB profiling）原範圍保留。無 commit／push／publish。

獨立複核：另一代理 `u2_resume_review`（實際 `gpt-5.6-luna / medium`）已讀報告並檢視 platform 影像，未發現額外 blocker；確認畫面限制及 server-stop／client-exit 區別。本次未修改 world 的陳述僅適用於接手稽核；先前建置的代表性 checkpoint 必須保留。`git diff --check`、新 checkpoint 結構及 U0–U11 表列檢查通過。

nextTask：`U2-baseline-runtime-recovery`。先確認未知 root Java／graphics 程序角色及可用顯示器，再恢復唯一一份隔離 baseline runtime；沿用未改動的 representative checkpoint、原設定與來源，設定 bounded readiness 窗口、重新核對 freeze／pose。復原後再補代表性水面與乾淨畫面。此為下一微任務，依 authoritative `stopAfterEachTask` 在本 checkpoint 停止；不把 runtime 不可用解讀為使用者撤回後續授權。

### U2-baseline-runtime-recovery checkpoint（2026-10-09）

本項 goal／primaryConcept：恢復唯一一份隔離 baseline runtime。readScope：最新 checkpoint、既有 launcher／GUI helpers、主機 Java／GPU identity 與 baseline log；writeScope：隔離 baseline runtime、`/tmp/lumen-u2-controlled-20261009/recovery-20261009`、ignored report 及本表。focusedValidation：程序唯一性、來源／設定一致、full/P16 readiness／takeover、pose/freeze 回應。doneCriteria：runtime 與控制讀回成功或具體阻擋；stopBoundary：啟動觀察最多 180 秒、進入世界至控制讀回最多 180 秒，完成本項即 checkpoint，不接候選或 renderer 修正。

- 主機 root Java `1615103` 已由 `-jar awtrix.jar` 辨識；啟動前完整 `nvidia-smi`（含 graphics）顯示無程序。只啟動一份 baseline，未動其他服務。Java 25 搭配 TotemCore Gradle 9.6.1 wrapper（Lumen 無 wrapper，版本與原 launcher 相同）；offline runClient，compile/resources tasks 均 UP-TO-DATE。舊日誌已複製保存。
- 新 client PID `591221`，exec session `83956`，私有 `DISPLAY :122`、window `2097199`、960×540，Xauthority 路徑保存在 recovery 的 `client-identity.json`。20:08:21 啟動，20:10:46 前主線已檢視標題畫面（145 秒內）；20:11:13 開始進世界，20:12:49 完成控制（96 秒）。跨 surface 仍須查實際存活，不能僅憑 exec handle 啟動第二份 client。
- 20:11:36 full renderer／P16 READY 且 WORLD TAKEOVER，內部 643×362；full／reflection source hash 皆匹配原 baseline。使用既有 shader／pipeline cache，不能當冷編譯或 FPS 結果。20:12:27 Pos `[-2.5,-59.0,7.5]`、20:12:37 Rotation `[0.0,10.0]`、20:12:45 frozen 讀回成功。既有平台維持 pose；本次未新增方塊。未重新讀回 time/weather，留待下一項。
- 原代表性 checkpoint 的 58 檔 hash／路徑再次一致；baseline options、renderer/server config 啟動前後 byte-identical。主線已檢視 in-world／controls-readback 畫面，玻璃噪點／紅色溢光及水面限制仍在。新 dev 玩家為 Player396（原為 Player461），玩家身份／動態物件不能宣稱精確配對。OpenAL 無裝置與 Realms dev token 警告已記錄；成功進世界不代表完整 lifecycle 驗收。
- 實際低階代理 `recovery_controls` 使用 `gpt-5.6-luna / medium` 提供唯讀控制 recipe；主線修正 Escape 會誤開選單及舊 DISPLAY／window 假設，再執行並讀回結果。證據：`build/reports/update-plan/u2-baseline-runtime-recovery.json`，recovery 目錄含日誌快照 SHA-256、設定 hash、client identity 及截圖。
- repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：本表、ignored report／runtime 證據；既有 planning index 保留。正式 source 未改，baseline 世界因正常載入與控制而可變，原共同 checkpoint 未改。沒有重跑完整 Gradle／shader／Mac suite；本項 runtime 檢查不能替代 U3–U11。無 commit／push／publish。

獨立複核：`recovery_review`（實際 `gpt-5.6-luna / medium`）核對日誌與畫面，指出報告的空白 native-error 欄位涵蓋過廣；已改成明確列出搜尋模式與快照範圍的 Vulkan error markers，另列 OpenAL／ALSA failure，不宣稱沒有所有原生錯誤。其餘無 runtime blocker。impact 僅 TotemLumen、無契約；`git diff --check`、U0–U11 表列與 whitespace 檢查通過。

nextTask：`U2b-representative-capture`，沿用目前唯一 baseline client，先核對 time/weather，再補可辨識水面與乾淨代表性畫面；玻璃噪點／溢光分類及候選配對保持未完成。當前 client 留在已凍結測試世界，不退出、不另開 GPU compiler。本項依微任務停止邊界交接。

### U2b-representative-capture checkpoint（2026-10-09 20:34）

本項 goal／primaryConcept：取得既有 baseline 的乾淨代表性畫面與可辨識水面證據。readScope：既有 runtime/checkpoint、GUI helper、scene 建置座標、本機 26.3 TimeCommand／timeline metadata；writeScope：隔離 runtime 控制、ignored 報告／圖片、本表。focusedValidation：時間／天氣／gamerule 回應、water block check、pose/freeze 還原、畫面與 hash；doneCriteria：保存畫面及限制，或具體失敗；stopBoundary：GUI 控制與擷取 240 秒內，不啟動候選、不修改 renderer／場景方塊。

- 主機核對 PID `591221`／launcher `588018` 仍存活，沿用同一 `:122` client。20:31:08–20:34:25 共 197 秒完成；没有重啟或新增 GPU client。原鏡頭 `representative-clean.png` 已實際檢視，F1 只暫時隱藏 HUD／聊天，擷取後恢復；renderer 品質不變。
- `/time query daytime` 在 26.3 失敗（不存在 `minecraft:daytime` timeline），失敗日誌保留。20:31:24／20:31:33 兩項 advance gamerule 讀回 false，20:31:42 晴天設定成功。20:33:37 `/time set noon` 回應 overworld clock **已在** `minecraft:noon`，故沒有改變 clock，補足先前時間讀回缺口。低階代理由本機 TimeCommand／Timelines 核對新語法為 `/time of minecraft:overworld query timeline minecraft:day`；本項未額外執行此查詢，不將靜態語法證據當 runtime 結果。
- camera-only 水面近景使用既有 stone rim 上 `(2.5,-59,15.5)`、yaw/pitch `(0,45)`；`water-oblique.png` 可見石框內半透明表面、池底及周邊幾何影像。20:33:00 `/execute if block 2 -60 17 minecraft:water` 回應 Test passed。原鏡頭已還原，20:33:10 Pos `[-2.5,-59.0,7.5]`、20:33:20 Rotation `[0.0,10.0]`、20:33:28 frozen 讀回成功。沒有新增方塊；水面可辨識不代表反射正確性或 U5 全部流體項目通過。
- 原代表性 checkpoint 58 檔 hash／路徑仍相同，options／renderer/server config 與啟動前 byte-identical。同一 session 的 full/P16 READY、來源 hash 與 takeover 沿用 recovery 證據。玻璃噪點與強烈紅色溢光仍可見，原因尚未證實；候選未啟動，畫質配對／FPS／動態身份一致性未驗收。
- 實際代理 `water_view_recipe` 為 `gpt-5.6-luna / medium`，提供唯讀水面鏡頭幾何及 26.3 時間語法；主線執行 GUI 並讀回。報告 `build/reports/update-plan/u2b-representative-capture.json`；圖片與本次 log snapshot 位於 `/tmp/lumen-u2-controlled-20261009/recovery-20261009`，SHA-256 已記錄。
- repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：本表、ignored report／helper／capture evidence；既有 planning index 保留。正式 source 未改，未重跑完整 build；既有世界受控制指令影響但共同 checkpoint 不變。U3–U11、Mac 外部 gate 與 server RGB profiling 保留。無 commit／push／publish。

獨立複核：`capture_visual_review`（實際 `gpt-5.6-luna / medium`）已看兩張影像並重算 image/log hashes，確認基本水面可辨識；「乾淨」僅指無 HUD／聊天遮擋，玻璃噪點／紅色溢光仍明顯，不能當畫質或 U5 驗收。無額外 blocker；live PID／設定／存檔 hash 沿用主線驗證，未冒充代理重跑。impact 僅 TotemLumen、無契約；`git diff --check`、U0–U11 表列及 whitespace 檢查通過。

nextTask：`U2-baseline-artifact-triage`，針對這兩張畫面，有界檢查 owning shader／material path，分類玻璃噪點及紅色溢光，形成單一修正與驗證規格；先讀既有架構／phase 文件，不直接改 renderer 或啟動候選。client 留在原鏡頭、HUD 已恢復、世界 frozen。依本項停止邊界 checkpoint。

### U2-baseline-artifact-triage checkpoint（2026-10-09）

本項 goal／primaryConcept：分類已觀察到的 baseline 材質異常，形成單一修正規格。readScope：既有兩張圖片與 hash、checkpoint 中六個方塊、P18 alpha／P15 transmission／P12 history 的 bounded symbols、既有架構及 phase 文件；writeScope：ignored triage report／讀取工具及本表。focusedValidation：圖片與 exported GLSL hash、貼圖 alpha histogram、NBT 方塊解碼、有效 shader 控制流程斷言；doneCriteria：已證實路徑與待證假說分列、具體修正／驗證規格；stopBoundary：不實作 renderer、不改 runtime、不啟動候選。

- `tinted_glass.png` 原版 16×16 alpha histogram 為 `{200:60,110:196}`，全部為中間 alpha；設定的 resourcePacks 清單為空。P18 `p18AlphaAccept` 依 frame seed 做隨機覆蓋；tinted glass 在 geometry/material resolver 都刻意排除 P15 透光，不能以改成普通透光玻璃來消除噪點。
- 已從與 runtime hash 完全一致的 `build/reports/compile-research/tint-20261003/full.baseline.comp` 複核最終路徑：mode 11 的 `giTemporalIndirectColor` 只累積間接光，`writeHistory` 存 indirectColor；`giCompositeFromIndirect` 每幀重新計算直接／局部光。另 `historyMatches` 嚴格比對 material／voxel／normal，alpha 取樣改變前景／後景／miss 時可能重設 GI sample count。**只放寬 identity 不足以解決直接亮度的 coverage 噪點**。這是已證實的靜態收斂缺口，尚非逐 GPU pixel 的因果量測。
- 紅玻璃的 baseline tint `(0.96,0.34,0.30)` 與 opacity `0.12` 經 P15 相乘，該衰減路徑本身不放大能量；glowstone 的預設 emission color `(1,0.78,0.42)` 與 bundled 規則一致。紅色染光有合理來源，但強度／光學正確性仍未驗收，不能宣稱所有紅色都是錯誤發光。NBT 核對確認場景中的 tinted/red stained glass、redstone block、glowstone、水；原建置腳本打算放 sea lantern 的 `(-3,-58,19)` 實際為 air，不再把它算作現有光源。
- 修正規格聚焦 **primary coverage radiance resolve**：需涵蓋直接／局部／間接光及 P16 合成順序，區分 stochastic hit/miss 與真實 disocclusion，保留 alpha=0／1、fractional expectation、tinted-glass exclusion、cutoff 與 resize/rejoin/close invalidation；不得凍結亂數、強制不透明或降畫質掩蓋噪點。現有 TextureAlphaTest／shader marker checks 沒有多幀收斂驗證；CR3 direct-temporal 專用化也不是 mode 11 的修正，沿用其既有限制。
- 實際低階代理 `alpha_temporal_triage` 使用 `gpt-5.6-luna / medium` 查 alpha/history；主線補查真正經 P12 patch 的 GI 路徑並用最終 GLSL hash 核對，沒有僅依 P5 原始 template 下結論。報告：`build/reports/update-plan/u2-baseline-artifact-triage.json`；NBT 讀取工具 `/tmp/lumen-u2-controlled-20261009/triage-fixture.py`。兩張圖 hash、兩種貼圖 histogram、六點方塊與 effective-source assertions 實際通過，沒有重跑 Gradle／native shader 或聲稱 runtime 修正通過。
- repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：本表、ignored report／reader；既有 planning index 保留。正式 source／world／config 未改。host `ps` 確認 PID `591221` 仍存活；本項沒有 GUI 操作、client 啟動或 build。畫質／FPS／生命週期／Mac／RGB profiling gate 全部保留。無 commit／push／publish。

獨立複核：`triage_review`（實際 `gpt-5.6-luna / medium`）重算 baseline GLSL hash、核对 mode 11 的 indirect-only history／直接光重合成，並執行唯讀 NBT reader 核對六點，無 blocker。靜態缺口不冒充 GPU 因果量測。impact 僅 TotemLumen、無共享契約；report JSON、下一微任務必要欄位、`git diff --check` 及 U0–U11 表列檢查通過。

nextTask：`U2-primary-coverage-contract`。primaryConcept 為 coverage history identity 與 radiance ownership；readScope 限 P12/P5 history、P16 合成／history buffer ownership 及本項證據；writeScope 僅 ignored contract report／本表。focusedValidation：逐項核對 full/P16 寫入順序、hit/miss/alpha 端點與真實世界變動的 invalidation、記憶體上限及生命週期 consumers。doneCriteria：唯一具體契約、受影響 symbols、bounded footprint 與收斂／disocclusion regression 規格，或明列未解決決策；stopBoundary：契約完成即停，renderer implementation 另拆微任務。不可直接停用全域 history rejection。

### U2-primary-coverage-contract checkpoint（2026-10-10）

本項 goal／primaryConcept：固定 coverage history identity 與最終 radiance ownership。readScope 限 P5/P12 history、P18 alpha accept、P16 線性合成／dispatch order、history allocation/lifecycle 與上一項 triage；writeScope 僅 ignored contract report 與本表。focusedValidation：核對 current write order、alpha endpoint、hit/miss/disocclusion、P16 ownership、8-word 雙 buffer footprint 與 lifecycle consumers；stopBoundary：不改 renderer、不啟動 candidate／GPU client。

- 現況確認：P12 mode 11 只累積 indirect history，direct/local/emission 每幀重算；P16 在 base dispatch 後讀 pixel、重做 primary trace、加 reflection 並套 gamma。既有 history identity 是 material/voxel/normal，fractional alpha 的 accept/reject 會變換 visible hit，因此 final primary coverage radiance 無法由現有 indirect-only history 收斂。
- 唯一契約為 `post-lighting-primary-coverage-resolve-v1`：base 只產生 current linear direct/local/GI 與 current identity metadata；P16 只加一次 current linear reflection、不碰 history／gamma；其後以 bounded resolve dispatch 驗證 previous identity、累積 final linear radiance、寫回 history 並套 gamma，再進既有 transfer barrier。
- fractional alpha 在 frame-seeded 決策前產生不含 frame seed 的 stable coverage cohort key；最接近的 partial candidate 同時錨定 accepted foreground 與 rejected background/miss。alpha=0 不形成 cohort，alpha=1／fixed cutoff 保持嚴格 identity。cohort/anchor mismatch、reprojection failure、scene/material/animation/settings/dimension/resize/rejoin/close 等既有 invalidation 仍立即 reset；不得停用全域 rejection。
- persistent footprint 維持雙 history buffer、每 record 8 words，即 64 bytes/pixel；1280×720 上限為 58,982,400 bytes（56.25 MiB），新增 persistent per-pixel bytes 為 0。history-write record 在 resolve 前作 current metadata、resolve 後作 accumulated record；需 versioned repack 並同步更新 spatial/history consumers，禁止新增第三 history buffer。
- regression 規格涵蓋 alpha 0/1/cutoff、25/50/75% deterministic expectation、同 cohort accept/reject 累計、真實幾何／材質／disocclusion reset、background hit/miss、P16 off/on 單次 reflection、gamma 後置、resize/rejoin/reload/close，以及 baseline/candidate generated-source ABI/compile。新增 resolve dispatch 的成本必須另以 timestamp/frame-time 量測，契約不宣稱 FPS。
- 前一個 baseline PID `591221` 在目前 surface 不可見；這不證明 clean JVM/Vulkan close，也不能列為 U3。沒有啟動 replacement client。正式 source/world/config 未改，沒有 shader/native/Mac/runtime 驗收。完整契約：`build/reports/update-plan/u2-primary-coverage-contract.json`。
- repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：本表與 ignored contract report；既有 planning index 保留。畫質配對、生命週期、FPS、Mac gate、server RGB profiling 仍未完成。無 commit／push／publish。

獨立複核：`coverage_contract_review`（實際 `gpt-5.6-luna / medium`）逐項核對 P18 stochastic alpha、P12 indirect-only history/direct recomposition、P16 dispatch／gamma、P5 history ABI 與 56.25 MiB 上限，結論 PASSED、無 blocking contract flaw。cohort key packing／collision strategy 明確留作下一實作項的 generated-source 測試義務，不被當成已完成實作。契約 JSON 結構與記憶體算式、`git diff --check`、U0–U11 表列均通過。

nextTask：`U2-primary-coverage-metadata`。primaryConcept 只做 stable fractional-coverage cohort identity transport 與 versioned 8-word history metadata layout；readScope 為本契約、P18 primary alpha trace、P5 所有 history ABI consumers；writeScope 限 owning Vulkan shader-patch/verifier sources、focused tests 與本表。focusedValidation：alpha endpoints、cohort 不含 frame seed、所有 ABI consumer 同步、generated-source／shader compilation。doneCriteria：metadata bounded 且 deterministically tested，final resolve 行為暫不改；stopBoundary：不做 post-P16 resolve dispatch、不啟動 candidate、不做視覺驗收。

### U2-primary-coverage-metadata checkpoint（2026-10-10）

本項 goal／primaryConcept：在既有 8-word history ABI 內傳遞 stable fractional-coverage cohort identity。readScope：coverage contract、P18 primary alpha trace、P5 history ABI consumers、P14／variant verifier；writeScope：擁有該路徑的 shader patch／verifier、ignored report 與本表。focusedValidation：alpha endpoints、cohort 不含 frame seed、primary-only capture、所有 ABI consumers、generated source／shaderc、unit tests 與完整 build；doneCriteria：metadata bounded 且 deterministic tests 通過，final resolve 行為保持不變；stopBoundary：不做 post-P16 resolve、不啟動 GPU client、不做視覺或 FPS 驗收。

- P18 現在只在 base primary `traceRay` 周圍開啟 coverage capture；shadow、GI 與 P16 reflection trace 不會寫入該狀態。alpha=0 拒絕且無 cohort，alpha=1 接受且無 cohort，fixed cutoff 保持嚴格 identity；只有無 cutoff 的 fractional alpha 以 texture handle、sampled texel coordinates 與 geometry salt 產生 stable cohort。frame seed 只參與 stochastic accept/reject，不進 cohort。
- 每次 primary trace 保存最近 fractional candidate 的 cohort 與 anchor distance，因此 accepted foreground 與 rejected background/miss 可共享穩定候選身份。Mesh normal 的計算提前到 alpha 判定前，accepted result 不變。capture 在 primary trace 後立即關閉，避免後續射線污染 metadata。
- P5 history ABI 升為 version 2，仍為每 record 8 words、雙 buffer，即 64 bytes/pixel；1280×720 上限 58,982,400 bytes（56.25 MiB），新增 persistent per-pixel memory 為 0。normal identity 改為 32-bit octahedral key，word 5/6 存 cohort／anchor distance，control word 帶 ABI version 與 cohort flag。Spatial/history consumer 已同步檢查 ABI v2。
- 本項刻意不讓 `historyMatches` 使用 cohort，final radiance ownership 與 temporal reuse 沒有改變。下一 resolve 必須同時核對 cohort 及重建後的 previous/current anchor positions；hash 單獨不得授權 reuse，collision／真實 disocclusion 必須 reset。
- Java 25 focused shader validation 通過：reference alpha/cohort/anchor/control-word checks 全部 PASS；baseline 與 candidate 的 full/reflection shaderc 均為 warnings=0、errors=0。`test`、`verifyDefaultShaderVariant` 及完整 `build` 通過；完整 build 同時驗證 baseline 與 `shared-filtered-trace` 的 intentional source identity 更新。原始精簡證據與 SHA-256 記在 `build/reports/update-plan/u2-primary-coverage-metadata.json`。
- 首次 Gradle 呼叫誤用 Java 17，於 compilation 前失敗；後續所有正式結果均用 Temurin 25。sandbox 最初阻擋 Gradle user-home lock，改用已核准的 repository wrapper 權限後完成；這兩項不列為來源失敗。
- repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：P18 coverage patch、P5 history ABI、P14 compile verifier、ShaderVariantVerifier、本表與 ignored report；既有 planning index 變更保留但不歸入本項。沒有啟動／重啟 GPU client，沒有 commit／push／publish。

獨立複核：`coverage_metadata_review`（實際 `gpt-5.6-luna / medium`）最初要求補足 reader、collision guard 與 deterministic verifier；修正後結論 PASSED。完整 build 另發現並修正受影響的 candidate generated-source hash，複核確認 scoped 且保留 baseline／invalid-source／routing／restart-boundary checks。剩餘風險為 resolve 的 anchor reprojection／collision rejection、native Vulkan／MoltenVK runtime、畫質／FPS／生命週期／Mac gate 與 server RGB profiling；本項不宣稱這些 gate 通過。

nextTask：`U2-primary-coverage-resolve`。goal：以目前 metadata 實作 post-lighting resolve；primaryConcept：final linear radiance 的 temporal ownership。readScope：coverage contract/report、P5 history、P16 dispatch/barrier/gamma 與本項 metadata；writeScope：owning Vulkan shader／dispatch／verifier sources及本表。focusedValidation：accepted/rejected deterministic convergence、嚴格 disocclusion reset、cohort+anchor collision rejection、P16 reflection 單次相加、resolve 後才 gamma、記憶體 footprint 不變，以及 baseline/candidate shader compile／build。doneCriteria：resolve 的 deterministic tests 與 compilation 完成，且不冒充視覺或 FPS 結果；stopBoundary：不啟動 GPU client／candidate、不做 runtime acceptance，另拆後續微任務。

### U2-primary-coverage-resolve checkpoint（2026-10-10）

本項 goal／primaryConcept：讓 post-lighting pass 擁有 final linear radiance 的 temporal accumulation。readScope：coverage contract／metadata、P5 history、P12 mode 11、P16 dispatch/barrier/gamma、P14E/P15 及 research transform consumers；writeScope：上述 owning shader／dispatch／verifier sources、ignored report 與本表。focusedValidation：accepted/rejected deterministic convergence、strict disocclusion、cohort+anchor collision rejection、reflection 單次相加、gamma 後置、temporal-off、8-word footprint、baseline/candidate shaderc 與完整 build；stopBoundary：不啟動 GPU client、不做 runtime 畫質／FPS 驗收。

- P12 mode 11 不再先累積 indirect-only history；它輸出當幀 linear direct/local/indirect 與 current metadata。既有 split P16 pipeline 綁定同一 scene 時，以 header word 94 接管 final resolve；word 46 仍只代表 optional reflection active，反射關閉時 resolve 仍執行。pipeline 尚未 ready 時由 base 套一次 gamma，保留可見 fallback frame。
- P16 最多加入一次 current linear reflection，再比較 previous/current history。Strict hit 需 ABI/material/voxel/normal 與重建 anchor 相符；fractional coverage 需非零 cohort 相同且 previous/current candidate anchors 在 pixel-footprint tolerance 內。cohort hash 單獨不能授權 reuse。Temporal=off（history limit 1）完全略過混合。
- 8-word record 的 word 6 現在對 fractional candidate 保存 candidate distance，對 strict hit 保存 primary distance；strict miss 無有限 world anchor。獨立 review 指出純 sky miss 會永遠 reset，已補成同 pixel、strict-miss identity 相同且前後 sky-ray dot ≥ 0.999999 才可 reuse；鏡頭旋轉立即 reset，fractional background miss 仍走 cohort＋anchor。
- Resolve 將 final linear result 與 sample count 寫回原 history write buffer，之後才套一次 vanilla gamma。仍為 ABI v2、每 record 8 words、雙 buffer、64 bytes/pixel；1280×720 上限 58,982,400 bytes（56.25 MiB），新增 persistent per-pixel memory 為 0。
- P14E exact-water reflection transform 改為只替換 optional reflection span，不再提前 return／套 gamma；P15 transmission consumer支援 mode 11 的 current-linear owner。PrimarySurfaceReuse research transform 保留 mode 10 的有界 reuse，並對新的 mode 11 owner fail closed。Baseline/candidate golden hashes已依 intentional generated-source 改變更新。
- Java 25 focused 驗證 `verifyRuntimeShader verifyCandidateRuntimeShader test` 通過；baseline/candidate full/reflection shaderc 與 deterministic resolve checks 通過。`verifyDefaultShaderVariant verifyOptInShaderVariant verifyPrimarySurfaceReuse build` 通過，完整 build 36 秒。證據 hash 與四份 source identity 記在 ignored `build/reports/update-plan/u2-primary-coverage-resolve.json`。
- repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。本項 source files 列於 report；先前 P18 metadata 與既有 planning index 變更保留。沒有啟動／重啟 GPU client，沒有 commit／push／publish。

獨立複核：`coverage_resolve_review`（實際 `gpt-5.6-luna / medium`）初查提出 strict miss 不累積的 MEDIUM finding；修正 sky-ray identity 後再次複核 PASS，無剩餘 concrete blocker。仍需 runtime 驗證 native Vulkan synchronization、實際玻璃收斂與視覺品質；新增 resolve 成本尚未量測，不構成 FPS 結論。Mac／MoltenVK、生命週期、候選配對與 server RGB profiling gate 均保留。

nextTask：`U2-primary-coverage-runtime-check`。goal／primaryConcept：在單一可確認的 native Vulkan client 上驗證 coverage resolve 的實際收斂與 fallback；readScope：本 checkpoint/report、既有代表性 fixture、client 身份與 runtime log；writeScope：ignored runtime log／captures／manifest 與本表，不改 renderer。focusedValidation：先確認不存在既有 client 或沿用唯一存活 client，核對新 full/reflection source hashes、resolve READY／dispatch、temporal off/on、玻璃 accepted/rejected 收斂、無 Vulkan validation/lifecycle error；doneCriteria：保存可比較畫面／log 或明確 runtime blocker；stopBoundary：只允許單一 client，不啟動候選配對、不量 FPS、不進 U3，且不發布。

### U2-primary-coverage-runtime-check checkpoint（2026-10-10）

本項 goal／primaryConcept：在單一隔離 Vulkan client 上驗證 coverage resolve 的實際呈現與收斂。readScope：前一 checkpoint/report、代表性 `LumenCandidateTest` fixture、client identity、runtime log 與 framebuffer；writeScope：ignored runtime evidence/report 與本表，不改 renderer。focusedValidation：單一 client、source identity、pipeline/binding/resolve READY、首個 resolved frame、temporal off/on、玻璃收斂與 Vulkan error；doneCriteria 允許以明確 runtime blocker 結案；stopBoundary：不啟動候選、不量 FPS、不進 U3、不發布。

- 啟動前未找到既有 TotemLumen／KnotClient；只啟動一個 baseline client（PID `939885`，Xvfb `:122`），run directory 為 `/tmp/lumen-u2-coverage-runtime-20261010/run`。隔離世界沿用代表性 checkpoint；沒有啟動第二個 client。
- 實際環境為 Minecraft 26.3、Java 25、Fabric Loader 0.19.5、Vulkan llvmpipe Mesa 25.2.8。這是 software Vulkan，不能冒充 native desktop GPU 或 Apple Silicon／MoltenVK 證據。
- full source SHA-256 `80a305216af5af79db02ccfae698dec4235ace7cffcacdb3f254126b6a898671`、reflection source SHA-256 `7a20b20b0a3fdb8b6a96439e71d19a69990137a715d322b7cc80642be01d09f7`，均精確匹配前一 checkpoint。兩條 pipeline prewarm、full/reflection scene binding、`Full lighting renderer READY` 與 `P16 post-lighting resolve READY` 均完成。
- READY 後沒有 `WORLD TAKEOVER`；X11 framebuffer 持續為 READY 前的同一張圖，SHA-256 `141cfb9fd3a5280596ce7ea384b066c40bf527b10af6bb623fe6eacf1a2a638a`。因此沒有 resolved frame，不能宣稱玻璃 accepted/rejected 收斂、視覺品質或 temporal off/on 通過。
- 兩份相隔 8 秒的 JVM thread sample 顯示 Render thread CPU time 都是 `59059.66ms`，top frame 與 call path 均停在 `vkDestroyBufferView` → `VulkanRenderPass.pushDescriptors` cleanup → `DestructionQueue.rotate` → `VulkanCommandEncoder.submit`。這是可重現的 runtime lifecycle blocker，不是單純等待 shader compile。停止觀察前沒有 VUID、`VK_ERROR` 或 device-lost log；此缺失不能證明 lifecycle 正確。Xvfb 的 OpenAL device failure 為既知非 renderer 問題。
- runtime log、frame、thread samples 與 SHA-256 已記於 ignored `build/reports/update-plan/u2-primary-coverage-runtime-check.json`。client 保留運行供下一個有界診斷使用；後續不得再啟動另一個 GPU client。repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。沒有 commit／push／publish。

nextTask：`U2-primary-coverage-lifecycle-diagnosis`。goal：定位 READY 後 `vkDestroyBufferView` 停滯的擁有權與觸發條件；primaryConcept：P16 resolve descriptor/buffer-view lifetime。resumeCheckpoint：沿用 PID `939885`、`:122`、上述 log/frame/thread samples 與 source hashes，禁止重新掃描或啟動第二個 client。readScope：P16 resolve dispatch、P12/P16 descriptor binding、RenderPearl bridge/mixin 與既有 lifecycle tests；writeScope：先限 ignored diagnosis report 與本表，只有定位到 module-owned fix 且可用 focused validation 證明時才另開實作微任務。focusedValidation：把新增 post-lighting resolve 前後的 buffer-view 建立、submit、deferred destruction 與 scene lease 對齊，區分 TotemLumen misuse、RenderPearl/llvmpipe limitation及 pre-existing behavior；doneCriteria：形成 path:line root-cause hypothesis 與最小可驗證修正邊界，或記錄外部 blocker；stopBoundary：不修改 renderer、不重啟 client、不進 temporal/visual/FPS/Mac/server profiling。

### U2-primary-coverage-lifecycle-diagnosis checkpoint（2026-10-10）

本項 goal／primaryConcept：定位 READY 後 `vkDestroyBufferView` 等待的擁有權與觸發條件。resumeCheckpoint 沿用唯一 client PID `939885`、Xvfb `:122`、既有 log/frame/thread samples 與 source hashes；未重新掃描 workspace、未啟動第二個 client。readScope 限 P16 dispatch、注入點、frame submission／fenced recycle 與 resolve shader；writeScope 僅 ignored diagnosis report 與本表；stopBoundary：不修改 renderer、不進 temporal/visual/FPS/Mac/server profiling。

- 更正上一 checkpoint 的暫時判讀：這不是已證實的 lifecycle deadlock。`P16 post-lighting resolve READY` 於 20:25:39 出現；同一 process 未重啟並於 20:32:12 完成首個 resolved frame、記錄 `WORLD TAKEOVER`。READY 至 takeover 約 393 秒。
- 等待期間 Java Render thread 停在 RenderPearl `VulkanRenderPass.pushDescriptors` cleanup 的 `vkDestroyBufferView`，但 OS thread sample 顯示多個 Mesa／llvmpipe worker 持續消耗 CPU；之後 fence callback、history READY 與 takeover 均前進。因此最符合證據的分類是 software Vulkan 首次 full-base＋P16 compute 的極高成本，以及 driver 在 submit-time destruction queue 的同步等待。
- ownership 核對：`P5StableLookupRendererMixin.java:143-163` 把 base 與 P16 記錄在同一 command buffer；`P16MultipassReflection.java:202-243` 只對同一 scene buffer 加 compute barrier、綁定 persistent descriptor set、再 dispatch，沒有建立／銷毀 `VkBufferView`；`VulkanFrameComputeBatch.java:60-70` 交給 RenderPearl encoder 並註冊 fenced recycle；`VulkanFrameCommandPool.java:65-83` 只在 `queueFencedTask` 後 reset command buffer。等待期間沒有 attach/detach/program close，沒有 module-owned buffer-view destruction path 可對應該 stack。
- P16 是第二個 full-resolution pass；reflection 開啟時 `P16ReflectionPassShader.java:461-525` 每 pixel 重新 primary trace、可選 reflection trace，再執行 final temporal resolve 與 gamma。這解釋 llvmpipe 的高負載，但目前沒有 per-pass GPU timestamp，不能精確把 393 秒分攤給 base、reflection 或 resolve。
- 同一 run 後續自然產生的 performance probe 顯示 20:33:17 `avgSubmitToCompleteMs=1598.819`／`effectiveTotemFps=0.6`。這只作 forward-progress 診斷，不是受控 U8 FPS 驗收，也不能外推 native Vulkan 或 MoltenVK。
- 結論：沒有足夠證據修改 descriptor／buffer-view lifetime；最小修正邊界為「不改 renderer」。完整證據與 SHA-256 記於 ignored `build/reports/update-plan/u2-primary-coverage-lifecycle-diagnosis.json`。唯一 client 仍存活，因先前延遲輸入目前停在 Friends List modal；後續需先恢復畫面，且不得啟動另一 client。沒有 commit／push／publish。

nextTask：`U2-primary-coverage-runtime-check-resume`。goal：沿用已 takeover 的唯一 client，完成 coverage resolve 的可見 runtime 證據；primaryConcept：temporal on 的穩定 fixture frame 與 accepted/rejected glass 收斂。resumeCheckpoint：PID `939885`、`:122`、run directory `/tmp/lumen-u2-coverage-runtime-20261010/run`、baseline source hashes與 diagnosis report；先處理 Friends List modal，禁止 restart／replacement client。readScope：目前 framebuffer、runtime log、既有 fixture/control manifest；writeScope：ignored captures／runtime manifest 與本表，不改 renderer。focusedValidation：恢復世界畫面、確認 takeover 持續、固定 camera/time/weather、等待至少 64 completed frames、保存兩張可比較畫面並檢查 Vulkan error；temporal-off 若無安全 hot reload path 則明確保留。doneCriteria：保存 temporal-on/glass evidence 或具體 UI/runtime blocker；stopBoundary：不啟動 candidate、不量正式 FPS、不進 U3/Mac/server RGB profiling、不發布。

### U2-primary-coverage-runtime-check-resume checkpoint（2026-10-10）

本項 goal／primaryConcept：沿用已 takeover 的唯一 client 取得 temporal-on 與玻璃 runtime 證據。resumeCheckpoint：PID `939885`、`:122`、既有 run directory／source hashes／diagnosis evidence；未 restart、未啟動第二個 client。readScope 限目前 framebuffer、runtime log、既有 control manifest 與 temporal settings symbol；writeScope 僅 ignored captures/report 與本表；stopBoundary：不啟動 candidate、不量正式 FPS、不進 U3/Mac/server RGB profiling。

- Friends List modal 已以 `Turn Off` 關閉並恢復 takeover 世界。隔離 checkpoint 的時間／天氣 gamerules 保留；唯一不持久化的 `/tick freeze` 已用慢速分段輸入重新套用，server 於 20:55:44、client 於 20:56:01 明確回報 `The game is frozen`。沒有再批次排入其他命令。
- freeze 前後及固定鏡頭樣本已保存。後一張可見噪點比前一張少，證明 temporal-on 有 forward progress；兩張 freeze 樣本 RMSE 為 `944.603`（normalized `0.0144137`），畫面仍在改變，不能把此 pair 當成飽和收斂。
- 視覺驗收未通過：玻璃仍有大量高頻噪點，天空／地面存在強烈紫／洋紅污染。舊代表性 baseline 畫面沒有相同程度的全域紫色，但目前 pose／HUD 並非逐像素配對，因此只列為 triage finding，不宣稱已定位 regression root cause。
- performance probe 每次以 10 warmup＋30 sample frames 記錄；freeze 後第一筆可見結果於 20:58:45，`avgSubmitToCompleteMs=19269.713`、`effectiveTotemFps=0.1`。此行只證明 software-Vulkan throughput 不足以在有界 runtime check 內確認 freeze 後 64 completed frames，不是 U8 正式 FPS 結論。
- temporal-off 有正式 settings UI/setter 路徑，但目前 UI／frame cadence 極慢，無法在同一 run 中安全完成 OFF→STABLE cycle；此項保留。停止時沒有 VUID、`VK_ERROR`、validation error 或 device-lost；只有既知 Xvfb OpenAL error。
- runtime log、三張 frame、hash 與 RMSE 記於 ignored `build/reports/update-plan/u2-primary-coverage-runtime-resume.json`。唯一 client 仍存活、世界 frozen、takeover active；不得啟動另一 client。repositoriesTouched：TotemLumen；branch：main；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。沒有 source 修改、commit、push 或 publish。

nextTask：`U2-primary-coverage-visual-triage`。goal：判斷紫／洋紅污染與玻璃殘餘噪點是否由 post-lighting resolve／gamma ownership 引入；primaryConcept：final-linear color ownership。resumeCheckpoint：沿用 frozen PID `939885`、兩張 temporal-on samples、舊 representative-clean 與 full/reflection source hashes，不重啟 client。readScope：P12 mode 11 output、P16 final resolve/gamma、environment radiance、pixel pack/unpack、舊／新 capture；writeScope：ignored triage report 與本表，不改 renderer。focusedValidation：以 source path:line 與 channel/color-space invariants排除 fixture/time差異，區分 gamma、radiance packing、history aliasing或環境輸入；doneCriteria：形成可反證的 root-cause hypothesis 與單一最小實作任務，或證明需 native GPU 配對才能判定；stopBoundary：不修改 shader、不切 temporal、不啟動 candidate／第二 client、不做 FPS/U3/Mac/server profiling。

### U2-primary-coverage-visual-triage checkpoint（2026-10-11）

本項先處理使用者回報的「渲染仍是隨機區塊」。現存唯一 client 仍是 PID `939885`，實際為 `TOTEM_LUMEN`、`baseline`，full/reflection pipeline 與 post-lighting resolve 均 READY，沒有 VUID、validation error 或 device-lost；沒有重啟或啟動第二個 client。此 client 的隔離 fixture 只有 `16/128` resident sections，不能重現一般世界的容量邊界，且 software Vulkan 約每幀 18–20 秒，因此既有紫色／高頻噪點畫面仍不足以單獨證明使用者所見的 section popping。

- 靜態路徑已定位一個可反證的 resident-set 根因：`P5StableLookupRenderer` 將 `MAX_SECTIONS` 與 `LOOKUP_CAPACITY` 都固定為 `128`；`nearestSections` 從 `RayScene` 的 `HashMap.values()` 只按浮點距離排序，隨後直接截斷前 128 筆，沒有完整 key tie-break、resident hysteresis 或視錐優先序。
- 既有 runtime 證據曾達到 `128/128 slots`、`lookupMaxProbe=84`。在容量已滿時，載入／卸載、同距離 section 的來源順序或相機跨越 cutoff 都會替換 resident section；`sectionSignature` 改變後會觸發 full scene upload，而 `canReuseHistory` 明確拒絕 full upload，因此畫面同時可能出現 section popping 與 temporal noise 重置。這條鏈與「隨機區塊」吻合，且不由 `renderProfile` 或 shader variant 設定造成。
- 本項沒有把紫／洋紅污染併入同一根因：final-linear/gamma 與 stochastic glass 仍是另一個視覺問題，需保留後續原生 GPU 配對。也尚未取得使用者實際新 JAR session 的 `P5 stable GPU lookup READY` 行；因此 resident cap 是強靜態根因與既有滿載證據，不冒充該 session 的直接 runtime confirmation。
- repositoriesTouched：TotemLumen；branch：`main`；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：只更新本執行表；沒有 renderer source 修改、commit、push 或 publish。

nextTask：`U2-resident-section-selection-stability`。goal：消除 128-section cutoff 造成的隨機 resident replacement；primaryConcept：deterministic bounded resident selection。readScope：`nearestSections`、`RayScene.sectionSnapshots`、stable slot allocator、scene signature 與現有 section/lookup tests；writeScope：只限 owning selection helper、focused tests 與本表。focusedValidation：相同 camera/input 任意 insertion order 得到相同 keys、同距離完整 key tie-break、cutoff 附近小幅移動保留 resident hysteresis、移除／新增 section 才產生可解釋 replacement、lookup load factor 不再以 128 bucket 容納 128 resident entries；doneCriteria：deterministic tests 與 shader/build checks 通過，並明列仍需重啟後的 native Vulkan 實景確認；stopBoundary：不改 temporal/gamma/glass、不重啟或啟動 client、不做 FPS/Mac/server RGB profiling、不發布。

### R0-performance-renderer-reference-study checkpoint（2026-10-11）

依使用者「效能優先、先調查其他光影做法」要求，本項在 U2 resident 實作前插入唯讀研究。goal／primaryConcept：以官方原始碼與文件比較 Minecraft renderer／shader 的效能架構，將可移植結論映射到 Totem Lumen。readScope：Sodium、Iris、Complementary Reimagined、VulkanMod、Nvidium、Distant Horizons、Minecraft PTGI 的官方來源，以及本表已定位的 P5 resident/lookup/history symbols；writeScope：`docs/PERFORMANCE_RENDERER_REFERENCE_STUDY.md`、planning index 與本表。focusedValidation：每個結論可追溯 primary source，明確區分外部事實與 Lumen 推論，並以目前 `128/128`、`lookupMaxProbe=84`、full-upload history rejection 證據校核優先序。doneCriteria：形成有次序、可拆成單一概念微任務的執行方案；stopBoundary：文件研究完成即停，不改 renderer、不重啟或啟動 client、不做 native FPS/Mac/server RGB profiling、不發布。

- 共同做法不是無限制提高光追資料量，而是先做 retained residency、可見性裁切、距離／LOD 分層、昂貴效果的獨立解析度與時間重建。Sodium 的 section graph／async culling／upload budget、Iris 的 raster G-buffer→deferred/composite 分層、Complementary 的分項品質 profile、Distant Horizons 的近景完整＋遠景 LOD，以及 PTGI 的 bounded voxel grid＋分離 GI denoise 均支持這個方向。
- 對 Lumen 的直接結論：先穩定 resident 選擇，再把 lookup capacity 與 resident capacity 解耦；其後才做局部 history invalidation、primary-hit reuse 和遠景 coarse representation。只提高 `MAX_SECTIONS` 會增加每 section 至少 16 KiB voxel payload 與 ray domain，不能解決 cutoff churn；lookup 由 128 擴至 256 buckets 的額外成本只有 2 KiB，可作獨立低風險任務，但不與 selection 實作混成一項。
- 可攜性結論：Nvidium 類 GPU-driven 技術只能 capability-gated；Vulkan compute 仍是 MoltenVK 正確性基線。Iris/OpenGL 實作細節不直接移植，採用的是 geometry visibility、pass separation、reduced-resolution effects 與 culling 原則。
- repositoriesTouched：TotemLumen；branch：`main`；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：新增研究文件、更新 planning index 與本表；正式 renderer source 未於本項修改。沒有 Gradle／shader/native suite，因本項只有文件研究；沒有 commit、push 或 publish。

nextTask 維持 `U2-resident-section-selection-stability`，但其 focused scope 只處理 deterministic total order、視錐優先與 cutoff hysteresis。lookup headroom／telemetry 拆為後續 `R2`，避免 selection 與 hash-layout 兩個主要概念同時修改。完成 R1 後才可進 R2；不重啟目前唯一 GPU client，native 實景證據延後至相關 implementation checkpoints。

### U2-resident-section-selection-stability / R1 checkpoint（2026-10-12）

本項 goal／primaryConcept：消除 unordered snapshot 與純距離 cutoff 引起的 resident replacement；只實作 deterministic bounded resident selection。readScope：P5 selection、`FrameSnapshot` camera ABI、`RayScene.sectionSnapshots()`、stable slot ownership、P5 roadmap 與 R0 研究；writeScope：`ResidentSectionSelector`、P5 owning call site、focused unit tests 與本表。focusedValidation：insertion-order independence、完整 section-key tie-break、相機視野／旋轉、近距離 off-screen 保留、cutoff hysteresis、resident 移除、dimension filter 與 capacity。stopBoundary：不改 lookup layout、temporal/gamma/glass、GPU section 上限，不重啟 client，不做 native FPS/Mac/server RGB profiling，不發布。

- 新 selector 以 section AABB bounding sphere 對 camera quaternion、vertical FOV 與 viewport aspect 做保守 frustum 相交；視野內 section 取得 16-block ranking bonus，不採用絕對優先，因此非常近的背後 geometry 仍可勝過很遠的可見 geometry。既有 resident 取得 8-block distance 與 frustum-boundary allowance，減少相機在 cutoff 附近微動時反覆換入／換出。
- 排序最後依 distance、section X/Y/Z 形成 total order；同一 frame/input 不再受 `HashMap.values()` insertion order 影響。正式 GPU ABI、`MAX_SECTIONS=128`、slot allocator、scene signature 與 full-upload history policy 都未改，R2/R3 各自保留。
- focused test 首次執行有 1/5 failure：測試把 camera X=21 誤當作已跨過 8-block path-distance hold threshold；production selector 未改，將 decisive fixture 改為 X=25 後通過。最後擴充為 8 項測試，Java 25 下 `ResidentSectionSelectorTest` 全部通過。
- deterministic validation：Java 25／Gradle 9.6.1 `build` 成功（28 tasks，含完整 unit/check、configured baseline/candidate shader verifiers、ABI/mixin/RGB checks、jar assemble）；新增最後兩項邊界測試後又執行 focused suite，成功。`git diff --check` 於 checkpoint 收尾另行核對。未啟動或重啟 Minecraft client，因此這是 source/build correctness，不冒充 native Vulkan 畫面或 FPS 證據。
- repositoriesTouched：TotemLumen；branch：`main`；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。R1 filesChanged：`src/main/java/dev/totem/lumen/scene/ResidentSectionSelector.java`、`src/client/java/dev/totem/lumen/vulkan/P5StableLookupRenderer.java`、`src/test/java/dev/totem/lumen/scene/ResidentSectionSelectorTest.java`、本表。其他既有 shader／planning 工作樹修改未由 R1 重寫或回復。無 commit、push、publish。

remainingRisks：本項未重啟或重新核對既有 client；任何先前已啟動的 session 都不會載入新 classes，尚未以新 selector 重現一般世界。128 residents／128 lookup buckets 的 load factor 1.0 與 `lookupMaxProbe=84` 仍未修正；任何 resident membership 變化仍會 full upload 並拒絕整幀 history。Mac/MoltenVK、生命週期、正式 FPS 與 server RGB profiling gates 均保留。

nextTask：`R2-lookup-headroom-and-telemetry`。goal／primaryConcept：將 lookup capacity 與 resident capacity 解耦並量測 collision 行為。readScope：`GpuSectionLookupTable`、P5 Java/GLSL constants、scene buffer offsets、lookup verifier/tests 與 R1 selector output；writeScope：只限 lookup ABI constants／packing、probe/load telemetry、focused tests與本表。focusedValidation：128 residents 的 table load factor 不超過 0.5、Java/GLSL mask/offset 一致、slot zero/negative coordinates/collision/miss 上限、scene allocation delta 精確且無 voxel resident 增長、baseline/candidate shader compilation。doneCriteria：focused tests與 build checks 通過；stopBoundary：不增加 `MAX_SECTIONS`、不改 selection/history/color，不重啟 client、不做 native FPS/Mac/server RGB profiling、不發布。

### R2-lookup-headroom-and-telemetry checkpoint（2026-10-14）

taskId／primaryConcept：`R2-lookup-headroom-and-telemetry`；只將正式 full/P16 scene lookup 與 128 resident 上限解耦並加入 collision/load 觀測。repositoriesTouched：TotemLumen；branch：`main`；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。R2 filesChanged：`GpuSectionLookupTable`／其 focused test、P5 stable renderer、P16 reflection shader、P14 shader verifier、shader variant hash verifier及本表；既有工作樹的 R1／coverage 變更保留。

- 正式 lookup 由 128 增至 256 buckets，`MAX_SECTIONS` 仍為 128，最滿 load factor 為 0.5。Java derived layout 與 full/P16 GLSL 同步為 voxel `1120`、section-light count `525408`、index `525536`、light data `526560`、material emission `528608`。static scene 只增加 512 words／2048 bytes，沒有增加 exact voxel residents。bootstrap 使用獨立 64-word scene ABI，維持 128 buckets／voxel base 576。
- `GpuSectionLookupTable.find` 回報 hit/miss 與實際 inspected buckets；`loadFactor()` 提供打包負載。P5 READY 與週期 performance probe 現在記錄 `lookupEntries`、capacity、load factor、max insertion probe。這些是 CPU scene-pack collision telemetry；尚未聲稱取得 GPU shader 每像素 miss 分布。
- focused validation：Java 25／Gradle 9.6.1 `GpuSectionLookupTableTest` 成功，涵蓋 slot zero、負座標、collision、packed ABI、128/256=0.5、hit/miss probe 上限。完整 `build` 最終成功（28 tasks）；full、P16、bootstrap shaderc 均零 error，baseline／candidate／hybrid variants、RGB checks 與 jar assemble 通過。第一次完整 build 只因 generated-source golden hash 如預期改變而失敗；更新為本次 full `e47b4720...0bc7`、reflection `6e47724f...34b9` 後，單項 gate 與完整 build 均重驗成功。
- 獨立 deterministic 複核重新由 layout 公式計算所有位址與 2048-byte delta，掃描正式 full/P16 不再含舊 `608/524896/525024/526048/528096` marker，確認 `MAX_SECTIONS=128`；`git diff --check` 通過。沒有啟動或重啟 Minecraft/GPU client，沒有執行 native Vulkan FPS、Mac/MoltenVK 或 server RGB profiling，也沒有 commit／push／publish。

remainingRisks：source/shader correctness 尚未證明新 ABI 在 native Vulkan client 的實景呈現；舊 client 不會載入新 classes。resident membership 改變仍 full-upload 並拒絕整幀 history；GPU 每像素 lookup miss／probe 分布尚未量測。U2 visual/candidate 配對、U3 lifecycle、U8 FPS、Mac gate 與 server RGB profiling 保留。

nextTask：`R3-resident-change-invalidation-contract`。goal／primaryConcept：定義 section/slot revision 到 affected pixel/tile 的局部 history invalidation 契約。readScope：R1 selector identity、R2 lookup ABI、scene signature/full-upload 判定、P5/P16 8-word history metadata與所有 invalidation consumers；writeScope：只限 ignored contract evidence與本表。focusedValidation：列出 add/remove/replacement/unchanged/resize/rejoin 的 revision 語意、slot reuse 防混淆、tile/pixel conservative coverage、bounded memory與 full-reset fallback，核對 full/P16 consumers。doneCriteria：唯一可實作契約、受影響 symbols、記憶體上限與 deterministic regression 規格完成；stopBoundary：contract only，不修改 shader/runtime、不啟動 client、不做 FPS/Mac/server RGB profiling、不發布。

### R3-resident-change-invalidation-contract checkpoint（2026-10-14）

taskId／primaryConcept：`R3-resident-change-invalidation-contract`；只定義 resident section revision 到局部 final-history rejection 的契約。repositoriesTouched：TotemLumen；branch：`main`；HEAD：`7fa11ec418dc02a253c8b60929f8c97a5b71c7a1`。filesChanged：ignored `build/reports/update-plan/r3-resident-change-invalidation-contract.md`／`.json` 與本表；正式 Java／GLSL source 未改。

- 現況證實 `sectionSignature` 任一 key/revision 變化都會 full upload，而 `canReuseHistory` 直接以 `!fullSceneUpload` 拒絕整幀 history。現有 8-word record 只有 primary voxel/normal/material、coverage cohort/anchor 與 sample/version，無法辨識 shadow/GI/reflection ray 是否依賴變動 section；只比較 primary hit 或投影 section AABB 都不足以安全局部重用。
- 唯一契約採每 16×16 render tile 的 128-bit static-slot dependency。full、shadow、GI、transmission、reflection traversal 每次成功 section lookup 都標記 slot；section-derived static light 另帶 owner slot。final resolve 在 full/reflection dependency writes 完成並 barrier 後，以 `(previousDeps | currentDeps) & changedSlots` 決定 tile rejection。previous union 捕捉 removal，current union 捕捉 addition/new occluder，同 slot replacement 由 monotonic slot generation 防 ABA。
- resident transition frame 暫停 P5 internal indirect-history read一幀，避免 current dependency 尚未完成前混入 stale secondary radiance；P5 仍寫 current 8-word record，後續獨立 P16 final-resolve dispatch可對 dependency-disjoint tiles沿用 previous final radiance。history record 維持 8 words／ABI v2；header word 95保留 explicit reuse/full-reset flags。resize、dimension/rejoin、transition gap、submission failure、material/tuning/resource/RGB 等無局部 ownership 的變更 fail closed full reset。
- bounded footprint：configured maximum 1280×720 共 3600 tiles；雙 dependency buffers 115,200 bytes、256-word light-owner table 1,024 bytes、changed-slot mask 16 bytes，合計新增 GPU storage 116,240 bytes before alignment；CPU 128 個 64-bit slot generations 為 1,024 bytes。`MAX_SECTIONS` 維持 128。
- focused validation／獨立 deterministic review：machine-readable JSON 重新計算 tiles、雙 buffer、owner/mask與 CPU generation footprint，逐項等於契約 expected values，並斷言 16 個 regression cases、8-word history與現行 `MAX_SECTIONS=128`／`!fullSceneUpload` gate／FIFO slot reuse evidence，輸出 `R3 contract invariant verification PASS`。正式 source 自 R2 成功 build 後未改，故未重跑相同 28-task suite；`git diff --check` 於 checkpoint 收尾執行。沒有啟動 client，沒有 commit／push／publish。

remainingRisks：這是契約，尚未建立 slot generation、dependency buffers、light owner ABI、barrier或獨立 resolve dispatch；GPU atomic contention、實際 tile rejection ratio、scene lease/deferred destruction與 native Vulkan/MoltenVK行為仍未知。Fluid、dynamic entity、animated texture、held light與 gameplay RGB 是獨立 producer，在具備同等 dependency identity 前不得宣稱 resident-local reuse。U2 visual配對、U3 lifecycle、U8 FPS、Mac gate與server RGB profiling保留。

nextTask：`R4-primary-hit-reuse-profiling-contract`。goal／primaryConcept：定位 full/P16 重複 primary traversal 的實際 pass ownership，定義可量測且不改畫質的 timestamp/counter ABI。readScope：P5 primary trace、P12 GI consumers、P16 reflection trace/dispatch、Vulkan command/timestamp capability與既有 performance probe；writeScope：只限 ignored profiling contract evidence與本表。focusedValidation：列出每 pass primary/secondary ray calls、可用 timestamp query lifecycle、CPU fallback、counter storage上限、MoltenVK capability fallback與同源 A/B量測規格。doneCriteria：形成單一 instrumentation implementation task與 pass-level acceptance thresholds；stopBoundary：不實作 primary-hit cache、不降低解析度/取樣/品質、不啟動 GPU client、不做正式 FPS/Mac/server RGB profiling、不發布。

## 可執行的下一批微任務

上表 U2 以後是階段清單，尚不是可直接委派的大包任務。輪到該階段時依具體 fixture／symbol 拆分，具備下列欄位後才交付執行；不得把整個 renderer 範圍直接傳給低階模型。

| id | goal / primaryConcept | readScope | writeScope | focusedValidation / doneCriteria | stopBoundary |
| --- | --- | --- | --- | --- | --- |
| U1a | 核對 baseline／candidate 同一選擇與轉換語意 | selector、filtered trace transform、P12/P16 source methods、variant verifier | ignored `build/reports/update-plan/u1a-routing-review.md` | path:line 靜態獨立 findings；沿用 U0 routing 結果，不冒充 runtime equivalence | 完成審查 checkpoint；不修改 shader，不延伸 tint／lifetime |
| U1b | 核對 tint CPU/GPU stride 與位址一致 | cube/mesh pack、surface ABI、對應 GLSL sampler、tint verifier | ignored U1b review report | 核對首末合法 record／相鄰欄位／baseline-candidate 共用 layout；容量風險另列 | 完成 checkpoint；不順手擴增 registry 或材質 |
| U1c | 核對接入是否維持 pipeline worker／scene lease 所有權 | P12/P16 variant 接入、binding/detach、worker gate 相關 methods | ignored U1c review report | 指出接入前後 ownership 差異；既有 lifetime tests 的範圍和原生缺口分列 | 完成 checkpoint；若需 lifetime 修正先重規劃，不自動改共享所有權 |
| U2a | 固定受控 runtime fixture | candidate runtime opt-in、既有 runner、ignored test-world/config metadata | ignored runtime fixture／manifest，不動使用中世界或正式設定 | 同源雙 variant、固定世界／鏡頭／時間／設定，manifest 含 source hash | fixture 完成 checkpoint；尚不宣稱任一 frame READY |
| U2b-setup | 啟動並固定 baseline 控制場景 | U2a-build manifest、隔離 launcher/config、26.3 本機命令證據 | baseline 測試副本與 ignored log／control manifest | 核對實際 profile/quality/window、同源 hash，記錄 camera/time/weather；fallback 尚不算畫面驗收 | 控制場景 checkpoint；不修改 source 或候選 run |
| U2-controlled-checkpoint | 匹配動態世界初始狀態 | U2b-setup 的世界／log、26.3 save/tick 控制語意 | 新的隔離 checkpoint／副本、ignored manifest；保留原模板 | 正常 save 完成、檔案 hash、camera/time/weather/gamerules一致；tick 恢復差異明列 | checkpoint 完成即停止；不聲稱畫面相等，不並行啟動第二份 GPU compiler |
| U2b | 取得原版完整呈現基準 | U2a manifest 與原版 readiness／dispatch log | ignored baseline log／frames／timing | full/P16 READY、dispatch、takeover 和實際 frame；超時只記下限／未完成 | 達到明確 readiness 或時間窗口後 checkpoint，禁止無限延長 |
| U2c | 取得匹配候選呈現基準 | U2a、U2b manifest，候選相同入口 | ignored candidate log／frames／timing | 同 fixture 且兩個 pass 實際呈現；畫面對照依 art-direction skill 做獨立視覺判讀 | 保存 checkpoint；畫質未通過不得將候選改成 default |

U3 的 resize、reflection toggle、world rejoin、cold-close、warm-close 各自拆項；U4–U7 每個 renderer family／fixture 各自拆項；U8 的量測與 U9 的實作分開。此規劃避免一次委派多個無法用單一 focused validation 證明完成的概念。

## 原始計劃與證據入口

- [整體 roadmap](ROADMAP.md)
- [目前接續紀錄](LUMEN_PROFILE_HANDOFF_2026-09-30.md)
- [候選 runtime 與 tint](SHADER_CANDIDATE_RUNTIME.md)
- [編譯研究](LUMEN_COMPILE_RESEARCH_PLAN.md)
- [效能實驗計劃](LUMEN_PROFILE_PERFORMANCE_PLAN.md)
- [Server gameplay-light roadmap](GAMEPLAY_LIGHTING_ROADMAP.md)
