// AutoBall 内置的最小 Shizuku AIDL 桩（标准 AIDL 语法）。
//
// 说明：本文件仅用于保证工程在任何环境下都能编译。
// Shizuku 的 Kotlin 接入（ShizukuClient）完全走反射，编译期不依赖本接口生成的类，
// 因此不需要也不应该引入官方 AIDL 里带自定义 transaction id 的方言写法。
package moe.shizuku.api;

interface IShizukuService {
    int getVersion();
    int getUid();
}
