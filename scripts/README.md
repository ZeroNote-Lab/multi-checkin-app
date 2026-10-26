# scripts

发版 / 联调用的仓库内工具脚本（tracked）。测试产物仍放 `test/`（已 gitignore）。

## smoke_ui.ps1

无头模拟器冒烟：启动 AVD → 安装 APK → 启动应用 → 校验首页关键文案与崩溃日志 → 截图 → 关闭模拟器。

```powershell
# 先构建
$env:JAVA_HOME='D:\ProgramData\DouBaoData\app_apk\build-env\jdk17\jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:\ProgramData\DouBaoData\app_apk\build-env\android-sdk'
& 'D:\ProgramData\DouBaoData\app_apk\build-env\gradle-8.5\bin\gradle.bat' assembleDebug

# 冒烟（默认 fast_test + app/build/outputs/apk/debug/app-debug.apk）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_ui.ps1

# 指定 AVD / 保留模拟器排查
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_ui.ps1 -Avd checkin_test -KeepEmulator
```

- 退出码：0 = 通过；1 = 安装 / 启动 / 文案 / 崩溃检查失败。
- 产物：`test/<版本号>/screens/smoke-<时间戳>/`（ui.xml、home.png，失败时附 logcat.txt），遵循 AGENTS 的版本分目录约定。
- 只覆盖「装得上、起得来、首页渲染、无崩溃」；业务流程回归仍按 `test/<版本号>/` 下的手工步骤执行。

## Room 迁移测试

schema 基线在 `app/schemas/`（v1 / v2 各一份），迁移用例：`app/src/androidTest/.../MigrationTest.kt`。

```powershell
# 先启动模拟器（可用上面的冒烟脚本带 -KeepEmulator 起一台）
& 'D:\ProgramData\DouBaoData\app_apk\build-env\gradle-8.5\bin\gradle.bat' connectedDebugAndroidTest
```

> 注意：AGP 跑完 connected 测试默认会卸载 APK（应用数据会被清空）。要保留模拟器数据，追加
> `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`，跑完再 `adb install -r` 装回。
