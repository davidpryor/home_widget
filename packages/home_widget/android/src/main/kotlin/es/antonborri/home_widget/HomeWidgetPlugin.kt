package es.antonborri.home_widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import kotlin.jvm.Throws

/** HomeWidgetPlugin */
class HomeWidgetPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    EventChannel.StreamHandler,
    PluginRegistry.NewIntentListener {
  private lateinit var channel: MethodChannel
  private lateinit var eventChannel: EventChannel
  private lateinit var context: Context
  private var shouldEncryptPrefs: Boolean = false
  private var masterKey: MasterKey? = null


  private var activity: Activity? = null
  private var receiver: BroadcastReceiver? = null
  private val doubleLongPrefix: String = "home_widget.double."

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "home_widget")
    channel.setMethodCallHandler(this)

    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "home_widget/updates")
    eventChannel.setStreamHandler(this)
    context = flutterPluginBinding.applicationContext
  }
    @Throws(IllegalStateException::class)
    private fun getPrefs(context: Context): SharedPreferences {
    if (!shouldEncryptPrefs) {
      return createSharedPreferences(context)
    }
    val masterKey = getOrCreateMasterKey(context)
    return createEncryptedSharedPreferences(context, masterKey)
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "enableAndroidEncryption"  -> {
          try {
            assertCanEncrypt()
          } catch (e: IllegalStateException) {
            result.error("-8", e.message, e)
            return
          }
        // only set once, throw exception if set again
        shouldEncryptPrefs = true
        getOrCreateMasterKey(context)
        result.success(true)
      }
      "saveWidgetData" -> {
        if (call.hasArgument("id") && call.hasArgument("data")) {
          val id = call.argument<String>("id")
          val data = call.argument<Any>("data")
          val prefs = getPrefs(context).edit()
          if (data != null) {
            prefs.putBoolean("$doubleLongPrefix$id", data is Double)
            when (data) {
              is Boolean -> prefs.putBoolean(id, data)
              is Float -> prefs.putFloat(id, data)
              is String -> prefs.putString(id, data)
              is Double -> prefs.putLong(id, java.lang.Double.doubleToRawLongBits(data))
              is Int -> prefs.putInt(id, data)
              is Long -> prefs.putLong(id, data)
              else ->
                  result.error(
                      "-10",
                      "Invalid Type ${data!!::class.java.simpleName}. Supported types are Boolean, Float, String, Double, Long",
                      IllegalArgumentException())
            }
          } else {
            prefs.remove(id)
            prefs.remove("$doubleLongPrefix$id")
          }
          result.success(prefs.commit())
        } else {
          result.error(
              "-1",
              "InvalidArguments saveWidgetData must be called with id and data",
              IllegalArgumentException())
        }
      }
      "getWidgetData" -> {
        if (call.hasArgument("id")) {
          val id = call.argument<String>("id")
          val defaultValue = call.argument<Any>("defaultValue")
          val prefs = getPrefs(context)

          val value = prefs.all[id] ?: defaultValue

          if (value is Long && prefs.getBoolean("$doubleLongPrefix$id", false)) {
            result.success(java.lang.Double.longBitsToDouble(value))
          } else {
            result.success(value)
          }
        } else {
          result.error(
              "-2",
              "InvalidArguments getWidgetData must be called with id",
              IllegalArgumentException())
        }
      }
      "updateWidget" -> {
        val qualifiedName = call.argument<String>("qualifiedAndroidName")
        val className = call.argument<String>("android") ?: call.argument<String>("name")
        try {
          val javaClass = Class.forName(qualifiedName ?: "${context.packageName}.${className}")
          val intent = Intent(context, javaClass)
          intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
          val ids: IntArray =
              AppWidgetManager.getInstance(context.applicationContext)
                  .getAppWidgetIds(ComponentName(context, javaClass))
          intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
          context.sendBroadcast(intent)
          result.success(true)
        } catch (classException: ClassNotFoundException) {
          result.error(
              "-3",
              "No Widget found with Name $className. Argument 'name' must be the same as your AppWidgetProvider you wish to update",
              classException)
        }
      }
      "setAppGroupId" -> {
        result.success(true)
      }
      "initiallyLaunchedFromHomeWidget" -> {
        return if (activity
            ?.intent
            ?.action
            ?.equals(HomeWidgetLaunchIntent.HOME_WIDGET_LAUNCH_ACTION) == true) {
          result.success(activity?.intent?.data?.toString() ?: "")
        } else {
          result.success(null)
        }
      }
      "registerBackgroundCallback" -> {
        val dispatcher = ((call.arguments as Iterable<*>).toList()[0] as Number).toLong()
        val callback = ((call.arguments as Iterable<*>).toList()[1] as Number).toLong()
        saveCallbackHandle(context, dispatcher, callback)
        return result.success(true)
      }
      "isRequestPinWidgetSupported" -> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
          return result.success(false)
        }

        val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)
        return result.success(appWidgetManager.isRequestPinAppWidgetSupported)
      }
      "requestPinWidget" -> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
          return result.success(null)
        }

        val qualifiedName = call.argument<String>("qualifiedAndroidName")
        val className = call.argument<String>("android") ?: call.argument<String>("name")

        try {
          val javaClass = Class.forName(qualifiedName ?: "${context.packageName}.${className}")
          val myProvider = ComponentName(context, javaClass)

          val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)

          if (appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(myProvider, null, null)
          }

          return result.success(null)
        } catch (classException: ClassNotFoundException) {
          result.error(
              "-4",
              "No Widget found with Name $className. Argument 'name' must be the same as your AppWidgetProvider you wish to update",
              classException)
        }
      }
      "getInstalledWidgets" -> {
        try {
          val pinnedWidgetInfoList = getInstalledWidgets(context)
          result.success(pinnedWidgetInfoList)
        } catch (e: Exception) {
          result.error("-5", "Failed to get installed widgets: ${e.message}", null)
        }
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  private fun getInstalledWidgets(context: Context): List<Map<String, Any>> {
    val pinnedWidgetInfoList = mutableListOf<Map<String, Any>>()
    val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)
    val installedProviders =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          appWidgetManager.getInstalledProvidersForPackage(context.packageName, null)
        } else {
          appWidgetManager.installedProviders.filter {
            it.provider.packageName == context.packageName
          }
        }
    for (provider in installedProviders) {
      val widgetIds = appWidgetManager.getAppWidgetIds(provider.provider)
      for (widgetId in widgetIds) {
        val widgetInfo = appWidgetManager.getAppWidgetInfo(widgetId)
        pinnedWidgetInfoList.add(widgetInfoToMap(widgetId, widgetInfo))
      }
    }
    return pinnedWidgetInfoList
  }

  private fun widgetInfoToMap(widgetId: Int, widgetInfo: AppWidgetProviderInfo): Map<String, Any> {
    val label =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          widgetInfo.loadLabel(context.packageManager).toString()
        } else {
          @Suppress("DEPRECATION") widgetInfo.label
        }

    return mapOf(
        WIDGET_INFO_KEY_WIDGET_ID to widgetId,
        WIDGET_INFO_KEY_ANDROID_CLASS_NAME to widgetInfo.provider.shortClassName,
        WIDGET_INFO_KEY_LABEL to label)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
    @Throws(IllegalStateException::class)
    private fun getOrCreateMasterKey(context: Context): MasterKey {
      assertCanEncrypt()
    if (masterKey != null) {
      return masterKey!!
    }
    masterKey = createHomeWidgetMasterKey(context)
    return masterKey!!
  }


  companion object {
    const val PREFERENCES = "HomeWidgetPreferences"
    const val SECURE_PREFERENCES = "EncryptedHomeWidgetPreferences"
    const val SECURE_KEY_NAME = "HomeWidgetKey"
    val SECURE_MASTER_KEY_SCHEME = MasterKey.KeyScheme.AES256_GCM
    val SECURE_KEY_SCHEME = EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV
    val SECURE_VALUE_SCHEME = EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    @Throws(IllegalStateException::class)
    internal fun assertCanEncrypt() {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        throw IllegalStateException("Android Encryption is only available on Android M and above")
      }
    }
    private const val INTERNAL_PREFERENCES = "InternalHomeWidgetPreferences"
    private const val CALLBACK_DISPATCHER_HANDLE = "callbackDispatcherHandle"
    private const val CALLBACK_HANDLE = "callbackHandle"

    private const val WIDGET_INFO_KEY_WIDGET_ID = "widgetId"
    private const val WIDGET_INFO_KEY_ANDROID_CLASS_NAME = "androidClassName"
    private const val WIDGET_INFO_KEY_LABEL = "label"
    fun createSharedPreferences(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    @Throws(IllegalStateException::class)
    fun createEncryptedSharedPreferences(context: Context, masterKey: MasterKey): SharedPreferences {
        assertCanEncrypt()
      return EncryptedSharedPreferences.create(
          context,
          SECURE_PREFERENCES,
          masterKey,
          SECURE_KEY_SCHEME,
          SECURE_VALUE_SCHEME)
    }
      @Throws(IllegalStateException::class)
      fun createHomeWidgetMasterKey(context: Context): MasterKey {
        assertCanEncrypt()
        return MasterKey.Builder(context, SECURE_KEY_NAME)
          .setKeyScheme(SECURE_MASTER_KEY_SCHEME)
          .setRequestStrongBoxBacked(true)
          .build()
    }
    private fun saveCallbackHandle(context: Context, dispatcher: Long, handle: Long) {
      context
          .getSharedPreferences(INTERNAL_PREFERENCES, Context.MODE_PRIVATE)
          .edit()
          .putLong(CALLBACK_DISPATCHER_HANDLE, dispatcher)
          .putLong(CALLBACK_HANDLE, handle)
          .apply()
    }

    fun getDispatcherHandle(context: Context): Long =
        context
            .getSharedPreferences(INTERNAL_PREFERENCES, Context.MODE_PRIVATE)
            .getLong(CALLBACK_DISPATCHER_HANDLE, 0)

    fun getHandle(context: Context): Long =
        context
            .getSharedPreferences(INTERNAL_PREFERENCES, Context.MODE_PRIVATE)
            .getLong(CALLBACK_HANDLE, 0)

    fun getData(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.addOnNewIntentListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    unregisterReceiver()
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.addOnNewIntentListener(this)
  }

  override fun onDetachedFromActivity() {
    unregisterReceiver()
    activity = null
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    receiver = createReceiver(events)
  }

  override fun onCancel(arguments: Any?) {
    unregisterReceiver()
    receiver = null
  }

  private fun createReceiver(events: EventChannel.EventSink?): BroadcastReceiver {
    return object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action.equals(HomeWidgetLaunchIntent.HOME_WIDGET_LAUNCH_ACTION)) {
          events?.success(intent?.data?.toString() ?: true)
        }
      }
    }
  }

  private fun unregisterReceiver() {
    try {
      if (receiver != null) {
        context.unregisterReceiver(receiver)
      }
    } catch (e: IllegalArgumentException) {
      // Receiver not registered
    }
  }

  override fun onNewIntent(intent: Intent): Boolean {
    receiver?.onReceive(context, intent)
    return receiver != null
  }
}
