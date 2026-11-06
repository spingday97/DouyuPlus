# DouyuPlus (斗鱼Plus)

基于 Xposed 框架的斗鱼客户端（适配 8.2.1.0）增强模块，推荐搭配 NPatch 免 Root 注入使用。

---

## 功能特性

- **内置可视化设置面板**：
  - 在「我的」页面做左上角点击 **斗鱼Plus** 图标即可实时开关各项增强功能。
- **画质增强**：
  - 进入直播间自动切换最高画质；
  - 自适应画质增强（运行时动态注入高通 SGSR 1.0 + AMD CAS 超分辨率算法）。
- **弹幕增强**：
  - 解锁所有直播间的弹幕「+1」功能；
  - 解除「+1」 CD 冷却限制；
  - 连击重复弹幕自动合并（`xN`）；
  - 去除弹幕装扮；
  - 发送弹幕自动追加隐形零宽字符后缀，防止重复弹幕限制。
- **纯净去广告与精简**：
  - 秒跳开屏广告；
  - 移除直播间右下角/侧边轮播广告、活动挂件及带货推荐入口；
  - 净化首页推荐流穿插广告；
  - 支持隐藏底部栏中间发帖「+」号、自定义启动默认页、替换娱乐页为**关注页**。
- **其他体验优化**：
  - 拦截官方版本升级强弹窗与后台静默下载；
  - 极速降载优化（精简后台弱网探测与预加载）；
  - 适配横屏全宽显示。

---

## 使用方法

### 推荐：使用 NPatch 免 Root 注入（无需 Root）

1. 下载斗鱼官方 `8.2.1.0` 安装包与 [Releases](https://github.com/ysl-ovo/DouyuPlus/releases) 中的最新模块 APK；
2. 安装并打开 [NPatch](https://github.com/7723mod/NPatch)；
3. 选择斗鱼安装包，嵌入 DouyuPlus 模块，配置选择模块并打包安装即可。

### 方式二：LSPosed 模块（需 Root）

1. 在 [Releases](https://github.com/ysl-ovo/DouyuPlus/releases) 下载并安装模块；
2. 打开 LSPosed 启用模块，作用域勾选「斗鱼」；
3. 强行停止斗鱼客户端后重新打开即可生效。

---

## 编译构建

```bash
cd DouyuPlus
./gradlew assembleRelease
```
编译产物位于 `DouyuPlus/app/build/outputs/apk/release/`。

---

## 项目结构

```text
├── DouyuPlus/                # Xposed 模块主工程 (Android Studio / Gradle)
│   ├── app/src/main/java/io/github/ysl_ovo/douyuplus/
│   │   ├── HookEntry.java    # 模块入口
│   │   └── hooks/            # 核心 Hook 逻辑 (画质、弹幕、去广告、防更新等)
│   └── gradlew               # Gradle 编译 Wrapper
└── .github/workflows/        # GitHub Actions 自动化 CI 构建工作流
```

---

## 免责声明

本项目仅供个人学习与技术交流使用，严禁用于任何商业用途；斗鱼及其相关商标与客户端知识产权均归武汉斗鱼网络科技有限公司所有。
