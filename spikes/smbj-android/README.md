# SMBJ Android 兼容性 Spike

此模块只用于 M0 兼容性验证，不属于 remoPhoto 正式应用，也不得被正式 `app` 模块依赖。

## 构建

```powershell
.\gradlew.bat :spikes:smbj-android:assembleDebug `
  :spikes:smbj-android:assembleRelease --console=plain
```

## 真机执行

1. 安装 Debug APK并启动 `com.remophoto.smbjspike/.SmbjSpikeActivity`。
2. 在设备界面输入测试共享配置；不要通过 adb Intent 或命令行传递密码。
3. 点击“运行 SMBJ Spike”，以界面 PASS/FAIL 和 `SmbjAndroidSpike` 结构化日志为证据。
4. 重复执行错误密码、离线、取消和连续读图场景；结束后确认日志、UI tree 和文件中没有凭据。

探针只列出共享根目录并验证 client → connection → session → share 的反向关闭链。完整目录、读图和压力矩阵在接入测试设备后继续实现与执行。
