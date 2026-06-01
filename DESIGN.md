---
name: AI Sport
description: AI 驱动的运动视频分析平台
colors:
  primary: "#059669"
  primary-deep: "#047857"
  primary-dim: "rgba(5,150,105,0.08)"
  neutral-bg: "#F9F7F4"
  neutral-surface: "#FFFDFB"
  neutral-surface-alt: "#F7F4F1"
  ink: "#18181B"
  ink-muted: "#52525B"
  semantic-green: "#16A34A"
  semantic-amber: "#F59E0B"
  semantic-red: "#EF4444"
typography:
  body:
    fontFamily: "DM Sans, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.55
  display:
    fontFamily: "DM Sans, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "26px"
    fontWeight: 700
    lineHeight: 1.2
  mono:
    fontFamily: "JetBrains Mono, Cascadia Code, monospace"
    fontSize: "13px"
    fontWeight: 500
rounded:
  sm: "8px"
  md: "12px"
  lg: "16px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "36px"
components:
  card:
    backgroundColor: "{colors.neutral-surface}"
    rounded: "{rounded.md}"
    padding: "24px"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#FFFFFF"
    rounded: "{rounded.sm}"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
  sidebar:
    width: "240px"
    backgroundColor: "{colors.neutral-surface}"
---

## Overview

AI Sport 是一个面向健身爱好者和教练的运动视频分析平台。用户上传训练视频后，系统通过 MediaPipe 进行异步姿态分析，生成逐次动作的多维度评估报告。界面设计遵循专业克制原则：数据优先，装饰让位于信息。

## Colors

翡翠绿为主强调色，传递健康、成长、能量的品牌信号。暖白底色提供柔和的阅读环境，与纯白表面形成微妙的层次对比。语义色（绿/琥珀/红）用于状态反馈和数据指标。

- `primary` `#059669` — 主要操作按钮、选中态、评分环
- `primary-dim` `rgba(5,150,105,0.08)` — 选中背景、hover 态
- `neutral-bg` `#F9F7F4` — 页面底色，微暖米白
- `neutral-surface` `#FFFDFB` — 卡片、侧边栏
- `ink` `#18181B` — 主文字
- `ink-muted` `#71717A` — 辅助文字、标签

## Typography

双字体系统：DM Sans 用于 UI 文本和标题，JetBrains Mono 用于所有数值指标和状态标签。等宽字体赋予数据专业感，DM Sans 保持可读性。中文回退到系统字体。

- 标题：26px / 700 / 行高 1.2
- 正文：15px / 400 / 行高 1.55
- 标签：11px / 600 / 大写 / 字距 0.5px
- 数据：13px / 500 / JetBrains Mono

## Elevation

使用微妙的 tinted 阴影（非纯黑）区分层级。无大面积投影，保持界面轻盈。Card hover 时边框加深 + 微弱阴影升起。

- `shadow-sm` — 紧贴表面
- `shadow` — 默认卡片 hover
- `shadow-glow` — 聚焦环（accen t 色光晕）

## Components

- **Card**：白色表面 + 1px 边框，无顶部装饰条。hover 时边框加深 + 轻微阴影。
- **Button Primary**：翡翠绿实底白字，`scale(0.98)` 按下反馈。
- **Button Ghost**：透明底 + 边框，hover 时变浅灰背景。
- **Sidebar**：240px 固定宽度，白色表面，sticky 定位。
- **Metric Card**：浅灰底 + 边框，大号 mono 数字，hover 时边框变为强调色。
- **Status Pill**：圆角胶囊，mono 字体，语义色映射（绿=完成，琥珀=处理中，红=失败）。

## Do's and Don'ts

- DO：用 mono 字体展示所有数值、指标、状态。让数据看起来可信。
- DO：保持卡片间距一致（16px gap）。不混用不同间距。
- DO：hover 态用边框变化 + 微阴影表达，不用背景颜色大跳变。
- DON'T：不用青/蓝色系作为强调色。
- DON'T：不用暗色主题。
- DON'T：不在卡片上使用侧条纹或顶部渐变色条。
- DON'T：不使用纯黑（`#000`）阴影或纯黑文字。
- DON'T：不要过多动效。动画限于 fadeIn/slideUp，无弹跳/弹性曲线。
