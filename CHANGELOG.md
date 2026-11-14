# Changelog

## v0.1.2 · 2026-11-13

- 中文：去掉应用自己画的手柄射线（之前和系统射线叠在一起，看到两条）。现在只用系统射线；鼓棒尖到鼓面 0.30 m 以内进入敲鼓状态，隐藏系统射线和手柄指示，只看到鼓棒；所有鼓棒离开 0.45 m 满 0.4 秒、切到摆放 / 选垫 / 载入、或退出应用时恢复。
- English: removed the app-drawn controller ray (it doubled up with the system ray). Only the system ray is used now; when a stick tip comes within 0.30 m of the pads the app enters drumming mode and hides the system ray and controller pointer so only the sticks show; it restores when every stick is more than 0.45 m away for 0.4 s, when you switch to placing / pad picking / loading, or when you quit.
- 模拟器 QA #3 通过（单测 56/56）；需要真机确认：敲鼓时只见鼓棒无射线 / 抬手回窗口恰好一条射线 / 敲鼓时手和手柄仍可见。
  Emulator QA #3 passed (56/56 unit tests); still to confirm on a headset: sticks only and no ray while drumming, exactly one ray when pointing back at the window, hands and controllers still visible while drumming.

## v0.1.1 · 2026-11-12

- 第一个公开版本，模拟器 QA 两轮通过。每次开窗鼓台自动让开窗口；同一鼓垫连敲 10 下音量一致；设置首屏加「叫回面前」和「退出应用」；确认框和英文按钮名改清楚。
- First release, two rounds of emulator QA. The deck moves out of the window's way every time the window opens; 10 hits on the same pad are equally loud; Bring back and Quit app on the first Settings page; clearer confirm dialogs and English button names.
