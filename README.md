# 轻记 Android

原生 Android 记账 App。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Room SQLite 本地数据库
- ML Kit 中文 OCR（2.0 新增）
- MVVM / ViewModel

## 功能

### 1.x 基础功能

- 记录每一笔账：金额、类别、日期、备注、所属计划
- 本地记忆：Room 数据库保存，关闭 App 后仍保留
- 本月预算：输入预算，自动计算已花/剩余/超支
- 花钱计划：用户自定义项目和预计金额
- 超支计算：每个项目显示计划金额、已花金额、剩余/超支
- 清新界面：薄荷绿、卡片式、移动端原生体验

### 2.0 新增：微信 / 支付宝图片自动记账

- 底部导航新增「识图」入口
- 可选择微信/支付宝付款截图、账单截图
- 使用本机 ML Kit OCR 识别文字，不上传图片
- 自动提取：金额、日期、商户/备注、来源（微信/支付宝）
- 自动猜测类别：餐饮、交通、购物、娱乐、学习、医疗、房租、其他
- 弹出确认框，用户可修改识别结果后保存
- 保存后自动写入本地 Room 数据库

> 注意：微信/支付宝截图样式很多，OCR 结果可能不完美，所以 2.0 设计为“识别后确认”，不是直接无确认入账。

## APK

2.0 OCR 版：

```text
D:\projects\qingji-ledger-android\qingji-2.0-ocr.apk
```

原始 debug APK：

```text
D:\projects\qingji-ledger-android\app\build\outputs\apk\debug\app-debug.apk
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
