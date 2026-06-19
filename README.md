# 轻记 Android

原生 Android 记账 App。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Room SQLite 本地数据库
- MVVM / ViewModel

## 功能

- 记录每一笔账：金额、类别、日期、备注、所属计划
- 本地记忆：Room 数据库保存，关闭 App 后仍保留
- 本月预算：输入预算，自动计算已花/剩余/超支
- 花钱计划：用户自定义项目和预计金额
- 超支计算：每个项目显示计划金额、已花金额、剩余/超支
- 清新界面：薄荷绿、卡片式、移动端原生体验

## APK

Debug APK 已生成：

```text
D:\projects\qingji-ledger-android\app\build\outputs\apk\debug\app-debug.apk
```

我也复制了一份：

```text
D:\projects\qingji-ledger-android\轻记.apk
```

## 构建

```powershell
cd D:\projects\qingji-ledger-android
.\gradlew.bat :app:assembleDebug
```

SDK 路径来自：

```text
D:\newandroid\Sdk
```
