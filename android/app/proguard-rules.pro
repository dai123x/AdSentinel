# 项目不使用反射调库,常规混淆即可;保留无障碍服务与被 JSON 反序列化的模型不会被
# R8 裁剪(模型为手写解析,不依赖反射,无需 keep)。
-keepattributes SourceFile,LineNumberTable
-dontwarn org.xmlpull.v1.**
