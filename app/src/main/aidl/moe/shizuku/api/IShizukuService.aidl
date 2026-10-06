// AutoBall 内置的最小 Shizuku AIDL 桩。
//
// 说明：本文件仅用于"无网络 / 拉取失败"时保证工程仍可编译。
// CI 中 scripts/fetch_shizuku_aidl.sh 会用 Shizuku 官方仓库的 AIDL 覆盖本文件，
// 只有官方 AIDL 才能保证与设备端 Shizuku 服务协议一致。
//
// AutoBall 的 Kotlin 代码不会在编译期引用本接口生成的类，
// 而是通过反射按方法名查找调用，因此本文件被覆盖不会造成编译失败。
package moe.shizuku.api;

interface IShizukuService {
    int getVersion() = 1;
    int getUid() = 2;
}
