# 轻记 App

轻记是一款清新的 Android 本地记账 App，主打“记得快、看得清、导入方便”。

它支持手动记账、预算计划、微信/支付宝截图识别、微信 Excel 账单导入，并且所有账单数据默认保存在手机本地，不依赖后端服务器。

## 功能亮点

### 1. 本地记账

- 记录每一笔支出
- 支持金额、类别、日期、备注、所属计划
- 数据保存在本地 Room SQLite 数据库
- 关闭 App 后数据仍然保留

### 2. 预算与花钱计划

- 设置本月总预算
- 添加花钱计划，例如：
  - 餐饮
  - 交通
  - 购物
  - 娱乐
  - 学习
  - 医疗
  - 房租
  - 其他
- 自动计算：
  - 已花金额
  - 剩余金额
  - 是否超支
  - 每个计划项的预算使用情况

### 3. 微信 / 支付宝截图识别

App 支持选择微信或支付宝账单截图，通过手机本地 OCR 自动识别账单内容。

识别后会自动提取：

- 金额
- 日期
- 商户 / 备注
- 来源：微信 / 支付宝
- 消费类别

识别结果不会直接入账，而是先进入确认列表，用户确认后再保存，避免误记账。

### 4. 微信 Excel / CSV 账单导入

支持导入微信官方导出的账单表格：

- `.xlsx`
- `.csv`

导入时会根据微信账单表头精准识别字段：

- 交易时间
- 交易类型
- 交易对方
- 商品
- 收 / 支
- 金额（元）
- 当前状态
- 备注

默认只导入“支出”记录，并过滤收入、退款、余额、合计等无关行。

### 5. 自动分类

导入或识别账单时，App 会根据商户和备注关键词自动分类。

示例：

| 关键词 | 分类 |
| --- | --- |
| 美团、外卖、饭店、奶茶 | 餐饮 |
| 滴滴、公交、地铁、高铁、加油 | 交通 |
| 淘宝、京东、拼多多、商城 | 购物 |
| 电影、游戏、会员、视频 | 娱乐 |
| 课程、书、培训 | 学习 |
| 医院、药、挂号 | 医疗 |
| 房租、物业、水电燃气 | 房租 |
| 无法判断 | 其他 |

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Room SQLite
- ML Kit 中文 OCR
- Apache POI Excel 解析
- Android Gradle Plugin

## 项目结构

```text
qingji-ledger-android
├── app
│   ├── build.gradle.kts
│   └── src/main
│       ├── AndroidManifest.xml
│       ├── java/com/qingji/ledger/MainActivity.kt
│       └── res
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 构建方式

确保本机已经安装 Android Studio / Android SDK。

然后执行：

```powershell
cd D:\projects\qingji-ledger-android
.\gradlew.bat :app:assembleDebug
```

构建成功后，APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 当前版本

当前版本：`2.2`

已实现：

- 手动记账
- 预算计划
- 本地数据保存
- 微信 / 支付宝截图 OCR 识别
- 微信 Excel / CSV 账单导入
- 自动分类
- 批量确认后入账

## 隐私说明

轻记默认不需要登录，也不上传账单数据。

- 手动账单：保存在本地数据库
- 截图 OCR：在手机本地识别
- Excel / CSV 导入：在手机本地解析

如果你自行二次开发并加入云同步或后端服务，请自行补充隐私政策和数据安全说明。

## 开源说明

本项目适合用于学习：

- Android 原生 App 开发
- Jetpack Compose UI
- Room 本地数据库
- OCR 识别
- Excel 表格解析
- 记账类 App 的基础业务逻辑

欢迎基于此项目继续改造，比如：

- 增加收入记录
- 增加导出 CSV / Excel
- 增加图表统计
- 增加搜索和筛选
- 增加多账本
- 增加云同步
- 优化 OCR 和分类规则

## License

暂未指定 License。正式开源前建议补充 MIT / Apache-2.0 等开源协议。
