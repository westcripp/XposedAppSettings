package de.robv.android.xposed.mods.appsettings;


import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setFloatField;
import static de.robv.android.xposed.XposedHelpers.setIntField;

import java.util.Locale;

import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XResources;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import de.robv.android.xposed.IXposedHookCmdInit;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.mods.appsettings.hooks.Activities;
import de.robv.android.xposed.mods.appsettings.hooks.PackagePermissions;

public class XposedMod implements IXposedHookZygoteInit, IXposedHookLoadPackage, IXposedHookCmdInit {
	public static XSharedPreferences prefs;
	
	@Override
	public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
		loadPrefs();
		
		// Hook to override DPI (globally, including resource load + rendering)
		try {
			if (Build.VERSION.SDK_INT < 17) {
				findAndHookMethod(Display.class, "init", int.class, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						String packageName = AndroidAppHelper.currentPackageName();

						if (!isActive(packageName)) {
							// No overrides for this package
							return;
						}

						int packageDPI = prefs.getInt(packageName + Common.PREF_DPI,
							prefs.getInt(Common.PREF_DEFAULT + Common.PREF_DPI, 0));
						if (packageDPI > 0) {
							// Density for this package is overridden, change density
							setFloatField(param.thisObject, "mDensity", packageDPI / 160.0f);
						}
					};
				});
			} else {
				findAndHookMethod(Display.class, "updateDisplayInfoLocked", new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						String packageName = AndroidAppHelper.currentPackageName();

						if (!isActive(packageName)) {
							// No overrides for this package
							return;
						}

						int packageDPI = prefs.getInt(packageName + Common.PREF_DPI,
							prefs.getInt(Common.PREF_DEFAULT + Common.PREF_DPI, 0));
						if (packageDPI > 0) {
							// Density for this package is overridden, change density
							Object mDisplayInfo = getObjectField(param.thisObject, "mDisplayInfo");
							setIntField(mDisplayInfo, "logicalDensityDpi", packageDPI);
						}
					};
				});
			}
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // Override settings used when loading resources
        try {
			findAndHookMethod(Resources.class, "updateConfiguration",
				Configuration.class, DisplayMetrics.class, "android.content.res.CompatibilityInfo",
				new XC_MethodHook() {

				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					if (param.args[0] != null && param.thisObject instanceof XResources) {
						XResources res = ((XResources) param.thisObject);
						String packageName = res.getPackageName();
						String hostPackageName = AndroidAppHelper.currentPackageName();
						boolean isActiveApp = hostPackageName.equals(packageName);
						
						// settings related to the density etc. are calculated for the running app...
						Configuration newConfig = null;
						if (hostPackageName != null && isActive(hostPackageName)) {
							int screen = prefs.getInt(hostPackageName + Common.PREF_SCREEN,
								prefs.getInt(Common.PREF_DEFAULT + Common.PREF_SCREEN, 0));
							if (screen < 0 || screen >= Common.swdp.length)
								screen = 0;
							
							int dpi = prefs.getInt(hostPackageName + Common.PREF_DPI,
									prefs.getInt(Common.PREF_DEFAULT + Common.PREF_DPI, 0));
							int fontScale = prefs.getInt(hostPackageName + Common.PREF_FONT_SCALE,
									prefs.getInt(Common.PREF_DEFAULT + Common.PREF_FONT_SCALE, 0));
							int swdp = Common.swdp[screen];
							int wdp = Common.wdp[screen];
							int hdp = Common.hdp[screen];
							int w = Common.w[screen];
							int h = Common.h[screen];
							
							boolean xlarge = prefs.getBoolean(hostPackageName + Common.PREF_XLARGE, false);
							
							if (swdp > 0 || xlarge || dpi > 0 || fontScale > 0) {
								newConfig = new Configuration((Configuration) param.args[0]);
								
								DisplayMetrics newMetrics;
								if (param.args[1] != null) {
									newMetrics = new DisplayMetrics();
									newMetrics.setTo((DisplayMetrics) param.args[1]);
									param.args[1] = newMetrics;
								} else {
									newMetrics = res.getDisplayMetrics();
								}
								
								if (swdp > 0) {
									newConfig.smallestScreenWidthDp = swdp;
									newConfig.screenWidthDp = wdp;
									newConfig.screenHeightDp = hdp;
								}
								if (xlarge)
									newConfig.screenLayout |= Configuration.SCREENLAYOUT_SIZE_XLARGE;
								if (dpi > 0) {
									newMetrics.density = dpi / 160f;
									newMetrics.densityDpi = dpi;
									
									if (Build.VERSION.SDK_INT >= 17)
										setIntField(newConfig, "densityDpi", dpi);
								}
								if (fontScale > 0)
									newConfig.fontScale = fontScale / 100.0f;
								if (w > 0) {
									newMetrics.widthPixels = w;
									newMetrics.heightPixels = h;
								}
							}
						}

						// ... whereas the locale is taken from the app for which resources are loaded
						if (packageName != null && isActive(packageName)) {
							Locale loc = getPackageSpecificLocale(packageName);
							if (loc != null) {
								if (newConfig == null)
									newConfig = new Configuration((Configuration) param.args[0]);

								newConfig.locale = loc;
								// Also set the locale as the app-wide default,
								// for purposes other than resource loading
								if (isActiveApp)
									Locale.setDefault(loc);
							}
						}

						if (newConfig != null)
							param.args[0] = newConfig;
					}
				}
			});
		} catch (Throwable t) {
			XposedBridge.log(t);
		}

		try {
			findAndHookMethod(NotificationManager.class, "notify", String.class, int.class, Notification.class, 
					new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					String packageName = AndroidAppHelper.currentPackageName();

					if (!isActive(packageName, Common.PREF_INSISTENT_NOTIF)) {
						// Not active for this package
						return;
					}

					Notification n = (Notification) param.args[2];
					n.flags |= Notification.FLAG_INSISTENT;
				}
			});
		} catch (Throwable t) {
			XposedBridge.log(t);
		}

        PackagePermissions.initHooks();
        Activities.hookActivitySettings();
	}

	
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
    	prefs.reload();

        // Override the default Locale if one is defined (not res-related, here)
        if (isActive(lpparam.packageName)) {
    		Locale packageLocale = getPackageSpecificLocale(lpparam.packageName);
    		if (packageLocale != null)
    			Locale.setDefault(packageLocale);
        }
    }

	private static Locale getPackageSpecificLocale(String packageName) {
		String locale = prefs.getString(packageName + Common.PREF_LOCALE, null);
		if (locale == null || locale.isEmpty())
			return null;

		String[] localeParts = locale.split("_", 3);
		String language = localeParts[0];
		String region = (localeParts.length >= 2) ? localeParts[1] : "";
		String variant = (localeParts.length >= 3) ? localeParts[2] : "";
		return new Locale(language, region, variant);
	}


	@Override
    public void initCmdApp(de.robv.android.xposed.IXposedHookCmdInit.StartupParam startupParam) throws Throwable {
		loadPrefs();
    }
	
	
	public static void loadPrefs() {
		prefs = new XSharedPreferences(Common.MY_PACKAGE_NAME, Common.PREFS);
	}
	
	public static boolean isActive(String packageName) {
		return prefs.getBoolean(packageName + Common.PREF_ACTIVE, false);
	}
	
	public static boolean isActive(String packageName, String sub) {
		return prefs.getBoolean(packageName + Common.PREF_ACTIVE, false) && prefs.getBoolean(packageName + sub, false);
	}
}
