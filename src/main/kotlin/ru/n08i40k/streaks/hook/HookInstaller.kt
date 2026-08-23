package ru.n08i40k.streaks.hook

import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Member

typealias InstallHook = (method: Member, callback: (XC_MethodHook.MethodHookParam) -> Unit) -> Unit
