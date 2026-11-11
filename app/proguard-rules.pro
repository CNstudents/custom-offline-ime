# 输入法服务由系统通过 Binder 反射实例化，必须保留类名与方法名
-keep class com.dingzhi.ime.CustomImeService { *; }
-keep class com.dingzhi.ime.SettingsActivity { *; }

# 保险起见保留 InputMethodService 子类
-keep class * extends android.inputmethodservice.InputMethodService { *; }

# 不混淆任何自定义 View（部分老系统会按类名反射查找构造函数）
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}
