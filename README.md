# 轻记 Android

原生 Android 记账 App。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Room SQLite 本地数据库
- ML Kit 中文 OCR
- MVVM / ViewModel

## 功能

### 基础功能

- 记录每一笔账：金额、类别、日期、备注、所属计划
- 本地记忆：Room 数据库保存，关闭 App 后仍保留
- 本月预算：输入预算，自动计算已花/剩余/超支
- 花钱计划：用户自定义项目和预计金额
- 超支计算：每个项目显示计划金额、已花金额、剩余/超支
- 清新界面：薄荷绿、卡片式、移动端原生体验

### 2.1 新增：微信 / 支付宝账单列表识图，自动分类批量记账

- 底部导航「识图」入口
- 可选择微信/支付宝账单列表截图或付款截图
- 使用手机本地 ML Kit 中文 OCR，不上传图片
- 从截图里尽量识别多条支出明细
- 对每条支出自动提取：金额、商户/备注、日期、来源
- 根据商户关键词自动分类：
  - 外卖/饭店/美团/奶茶 → 餐饮
  - 滴滴/公交/地铁/高铁/加油 → 交通
  - 淘宝/京东/拼多多/商城 → 购物
  - 电影/游戏/会员/视频 → 娱乐
  - 课程/书/培训 → 学习
  - 医院/药/挂号 → 医疗
  - 房租/物业/水电燃气 → 房租
  - 其他无法判断 → 其他
- 弹出批量确认列表，用户可以删除误识别项，手动改分类后批量保存

> 注意：微信/支付宝截图格式很多，OCR 结果可能不完美，所以设计为“识别 → 确认 → 批量保存”，避免误入账。

## APK

2.1 微信/支付宝批量识图版：

```text
D:\projects\qingji-ledger-android\qingji-2.1-wechat-alipay-batch.apk
```

2.0 单笔 OCR 版：

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
