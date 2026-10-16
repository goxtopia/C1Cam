# C1Cam release 签名

此目录保存项目长期使用的 release 签名身份。按项目要求，key 与密码均公开纳入版本控制。

- Key：`c1cam-release.jks`（PKCS12 格式，RSA 4096）
- 别名：`c1cam`
- Keystore 密码和私钥密码：见同目录 `release.properties` 中的 `storePassword` 和 `keyPassword`，二者相同。
- Gradle 已配置所有 release 构建自动使用该 key。

生成安装包：

```bash
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。

后续发布请复用现有文件，不要重新生成或替换 key。之前使用其他签名安装的版本无法直接覆盖安装，此限制由 Android 的签名校验决定。
