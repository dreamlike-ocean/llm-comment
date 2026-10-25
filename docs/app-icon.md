# 桌面应用图标

`comment-desktop/src/main/resources/io/github/dreamlike/llmcomment/desktop/app-icon.png` 由内置 `image_gen` 工具生成，无参考图。保留生成的原始 PNG，未重绘或缩放。实际尺寸为 1254 × 1254，RGBA，四角透明；提示词要求的 1024 × 1024 未被生成工具采用。紫色圆角底配合白色代码行与评论气泡，供窗口图标使用。

实际提示词：

```text
Use case: logo-brand
Asset type: desktop application icon, 1024x1024 PNG
Primary request: one polished icon for a local source-code commenting application. A solid purple #6750A4 rounded-square tile containing a bold white symbol that fuses simple horizontal code lines with a comment speech bubble.
Style/medium: Material-style flat vector-like graphic, clean geometric silhouettes, no gradients, no texture, no shadows.
Composition/framing: centered, one icon only, tile occupies about 90% of square canvas, generous consistent internal spacing, large bold shapes and high contrast readable at 24px.
Scene/backdrop: genuinely transparent outside the rounded square, including transparent outer corners.
Constraints: 1024x1024 square canvas; white code-line and speech-bubble symbol; purple rounded square; preserve clean edges.
Avoid: text, letters, numbers, watermark, surrounding scene, background beyond tile, multiple icon variants or grid, tiny details.
```

调用参数为 `transparent_background=true`。仅检查 PNG 尺寸、RGBA 通道及四角 alpha，并目视确认图形；未对像素内容进行后处理。
