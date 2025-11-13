<div align="center">
  <img src="./app/src/main/ic_launcher-round.png" alt="GeekPaste" width="128" height="128">
  <h1>即刻贴 (GeekPaste)</h1>

[![GitHub release](https://img.shields.io/github/v/release/h3110w0r1d-y/GeekPaste)](https://github.com/h3110w0r1d-y/GeekPaste/releases)
[![CI/CD](https://github.com/h3110w0r1d-y/GeekPaste/actions/workflows/release.yml/badge.svg)](https://github.com/h3110w0r1d-y/GeekPaste/actions/workflows/release.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B-blue)
![macOS](https://img.shields.io/badge/macOS-14%2B-blue)
![License](https://img.shields.io/github/license/h3110w0r1d-y/GeekPaste)
![GitHub stars](https://img.shields.io/github/stars/h3110w0r1d-y/GeekPaste?style=social)
</div>

---

## 项目简介

### 🚧 软件还在开发中，目前Android与macOS的剪贴板同步功能可以正常使用

基于低功耗蓝牙(BLE)的跨平台剪贴板同步软件。实现类似iPhone与mac的丝滑同步体验。

Android作为中心设备，macOS、Windows作为边缘设备。

支持局域网文件分享。

软件本身也是Xposed模块，用于实现 Android 10+ 后台读取剪贴板功能，Hook 位置：[Hook.kt](./app/src/main/java/com/h3110w0r1d/geekpaste/Hook.kt)

推荐[不死鸟保活模块](https://github.com/h3110w0r1d-y/Phoenix)，搭配食用口感更佳！

## 下载应用

  - Android/macOS: [Latest Release](https://github.com/h3110w0r1d-y/GeekPaste/releases/latest)
  - Windows: ~~(新建文件夹)~~

## 使用说明



## 画饼

  - [x] Android <-> macOS 剪贴板互相同步
  - [x] Android -> macOS 分享文件
  - [ ] macOS -> Android 分享文件
  - [ ] Windows 客户端

如有建议或问题欢迎提交 Issue 反馈。
