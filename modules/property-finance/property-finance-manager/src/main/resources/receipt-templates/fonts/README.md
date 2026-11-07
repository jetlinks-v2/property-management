# 收据中文字体

`PropertyReceiptSC-Regular.ttf` 来源于 Noto Sans SC 可变字体，遵循同目录的 SIL Open Font License 1.1。原字体的默认字重为100，PDF引擎未选择可变轴时会打印得过细；本文件固定为400常规字重，随服务打包，不依赖宿主系统字体或外部网络。

生成方式为 FontTools 的 `instantiateVariableFont(font, {"wght": 400}, inplace=True)`，随后将字体家族、完整名称及 PostScript 名称改为 `Property Receipt SC` / `PropertyReceiptSC-Regular`。未裁剪中文字符集。更换字体时必须保留许可证，验证中文、金额大写、长明细分页及PDF实际渲染效果。

上游字体与许可证来源：<https://github.com/notofonts/noto-cjk>，`Sans/LICENSE`。字体同形字可能在PDF文本提取时返回Unicode兼容部首；文本行为测试使用NFKC规范化比较，视觉内容仍保持原字形。
