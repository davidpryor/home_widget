import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.annotation.RestrictTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.glance.state.GlanceStateDefinition
import androidx.security.crypto.MasterKey
import es.antonborri.home_widget.HomeWidgetPlugin
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HomeWidgetGlanceState(val preferences: SharedPreferences)
class EncryptedHomeWidgetGlanceState(val preferences: SharedPreferences)

class HomeWidgetGlanceStateDefinition() : GlanceStateDefinition<HomeWidgetGlanceState> {
    override suspend fun getDataStore(
      context: Context,
      fileKey: String
  ): DataStore<HomeWidgetGlanceState> {
        val preferences = HomeWidgetPlugin.createSharedPreferences(context)
    return HomeWidgetGlanceDataStore(preferences)
  }

  override fun getLocation(context: Context, fileKey: String): File {
    return Environment.getDataDirectory()
  }
}

class EncryptedHomeWidgetGlanceStateDefinition() : GlanceStateDefinition<EncryptedHomeWidgetGlanceState> {
    private var masterKey: MasterKey? = null
    private fun getMasterKey(context: Context): MasterKey {
            if (masterKey == null) { masterKey = HomeWidgetPlugin.createHomeWidgetMasterKey(context) }
            return masterKey!!
        }
    override suspend fun getDataStore(
        context: Context,
        fileKey: String
    ): DataStore<EncryptedHomeWidgetGlanceState> {
        val preferences = HomeWidgetPlugin.createEncryptedSharedPreferences(context, getMasterKey(context))
        return EncryptedHomeWidgetGlanceDataStore(preferences)
    }

    override fun getLocation(context: Context, fileKey: String): File {
        return Environment.getDataDirectory()
    }
}

private class HomeWidgetGlanceDataStore(private val preferences: SharedPreferences) :
    DataStore<HomeWidgetGlanceState> {
    override val data: Flow<HomeWidgetGlanceState>
        get() = flow { emit(HomeWidgetGlanceState(preferences)) }

    override suspend fun updateData(
        transform: suspend (t: HomeWidgetGlanceState) -> HomeWidgetGlanceState
    ): HomeWidgetGlanceState {
        return transform(HomeWidgetGlanceState(preferences))
    }
}
private class EncryptedHomeWidgetGlanceDataStore(private val preferences: SharedPreferences) :
    DataStore<EncryptedHomeWidgetGlanceState> {
    override val data: Flow<EncryptedHomeWidgetGlanceState>
        get() = flow { emit(EncryptedHomeWidgetGlanceState(preferences)) }

    override suspend fun updateData(
        transform: suspend (t: EncryptedHomeWidgetGlanceState) -> EncryptedHomeWidgetGlanceState
    ): EncryptedHomeWidgetGlanceState {
        return transform(EncryptedHomeWidgetGlanceState(preferences))
    }
}
