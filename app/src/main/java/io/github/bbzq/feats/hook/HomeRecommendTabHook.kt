package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.RestoredHomeRecommendTabSymbols
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class HomeRecommendTabHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var removeAllSkippedLogged = false
    private var bangumiInjectionFailureLogged = false

    override fun startHook() {
        if (env.processName != env.packageName) return
        ModuleSettings.refreshKnownHomeRecommendTabsCache(prefs)

        val symbols = env.symbols?.homeRecommendTabs?.restore(classLoader)
        if (symbols == null) {
            log("startHook: HomeRecommendTabs missing symbols")
            return
        }

        env.hookBefore(symbols.buildTabsMethod) { param ->
            runCatching {
                processTabsArgument(param.args, symbols)
            }.onFailure {
                log(
                    "HomeRecommendTabs failed at ${symbols.buildTabsMethod.declaringClass.name}.${symbols.buildTabsMethod.name}",
                    it,
                )
            }
        }
        log("startHook: HomeRecommendTabs method=${symbols.buildTabsMethod.declaringClass.name}.${symbols.buildTabsMethod.name}")
    }

    private fun processTabsArgument(
        args: MutableList<Any?>,
        symbols: RestoredHomeRecommendTabSymbols,
    ) {
        val original = args.firstOrNull() as? List<*> ?: return
        val tabs = original.toMutableList()
        if (ModuleSettings.isAddBangumiEnabled(prefs)) {
            tabs.clear()
            tabs.addAll(injectBangumiTabs(original, symbols))
        }
        val tabsChanged = tabs.size != original.size
        if (tabs.isEmpty()) return

        val entriesByIndex = tabs.mapIndexedNotNull { index, item ->
            item?.extractTabEntry(index, symbols)
        }.associateBy { it.index }
        if (entriesByIndex.isEmpty()) {
            if (tabsChanged) args[0] = tabs
            return
        }

        saveKnownTabs(entriesByIndex.values.sortedBy { it.order })

        val enabled = ModuleSettings.isCustomHomeRecommendTabFilterEnabled(prefs)
        val hiddenTabs = ModuleSettings.getHiddenHomeRecommendTabs(prefs)
        if (!enabled || hiddenTabs.isEmpty()) {
            if (tabsChanged) args[0] = tabs
            return
        }

        val filtered = ArrayList<Any?>(original.size)
        var removed = 0
        tabs.forEachIndexed { index, item ->
            val entry = entriesByIndex[index]
            if (entry != null && entry.key in hiddenTabs) {
                removed += 1
            } else {
                filtered += item
            }
        }

        if (removed == 0) {
            if (tabsChanged) args[0] = tabs
            return
        }
        if (filtered.isEmpty()) {
            if (!removeAllSkippedLogged) {
                removeAllSkippedLogged = true
                log("HomeRecommendTabs skipped filtering because all tabs would be removed")
            }
            return
        }

        args[0] = filtered
        log("HomeRecommendTabs removed $removed tab(s)")
    }

    private fun injectBangumiTabs(
        tabs: List<Any?>,
        symbols: RestoredHomeRecommendTabSymbols,
    ): List<Any?> {
        val tabClass = symbols.idField.declaringClass
        val constructor = runCatching {
            tabClass.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull()
        if (constructor == null) {
            logBangumiInjectionFailure("Tab resource has no no-arg constructor")
            return tabs
        }

        tabs.filterNotNull().forEach { tab ->
            val originalUri = tab.readString(symbols.uriField)
            val spec = BangumiHomeTabs.forExistingUri(originalUri) ?: return@forEach
            if (initializeBangumiTab(tab, tabClass, spec, symbols)) {
                val action = if (originalUri == spec.uri) "normalized" else "migrated"
                log("HomeRecommendTabs $action ${spec.describe()}: $originalUri -> ${spec.uri}")
            }
        }

        return BangumiHomeTabs.appendMissing(
            existing = tabs,
            uriOf = { item -> item.readString(symbols.uriField) },
        ) { spec ->
            val tab = runCatching { constructor.newInstance() }.getOrNull()
            if (tab == null) {
                logBangumiInjectionFailure("Unable to construct ${spec.title}")
                return@appendMissing null
            }
            if (initializeBangumiTab(tab, tabClass, spec, symbols)) {
                log("HomeRecommendTabs injected ${spec.describe()}")
                tab
            } else null
        }
    }

    private fun BangumiHomeTabSpec.describe(): String =
        "$title: id=$id, reporter=$reporterId, position=$position, uri=$uri"

    private fun initializeBangumiTab(
        tab: Any,
        tabClass: Class<*>,
        spec: BangumiHomeTabSpec,
        symbols: RestoredHomeRecommendTabSymbols,
    ): Boolean = runCatching {
        symbols.idField.set(tab, spec.id)
        symbols.titleField.set(tab, spec.title)
        symbols.uriField.set(tab, spec.uri)
        symbols.reporterIdField?.set(tab, spec.reporterId)
        findPositionField(tabClass)?.setPosition(tab, spec.position)
        true
    }.onFailure {
        logBangumiInjectionFailure("Unable to initialize ${spec.title}: ${it.javaClass.simpleName}")
    }.getOrDefault(false)

    private fun logBangumiInjectionFailure(message: String) {
        if (bangumiInjectionFailureLogged) return
        bangumiInjectionFailureLogged = true
        log("HomeRecommendTabs bangumi injection skipped: $message")
    }

    private fun findPositionField(type: Class<*>): Field? = generateSequence(type) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) &&
                !Modifier.isFinal(field.modifiers) &&
                field.name in POSITION_FIELD_NAMES &&
                field.type in POSITION_FIELD_TYPES
        }
        ?.apply { isAccessible = true }

    private fun Field.setPosition(target: Any, position: Int) {
        when (type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> set(target, position)
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> set(target, position.toLong())
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> set(target, position.toShort())
            else -> Unit
        }
    }

    private fun Any.extractTabEntry(
        index: Int,
        symbols: RestoredHomeRecommendTabSymbols,
    ): HomeRecommendTabEntry? {
        val id = readString(symbols.idField)
        val title = readString(symbols.titleField)
        val uri = readString(symbols.uriField)
        val reporterId = readString(symbols.reporterIdField)
        val key = listOf(id, reporterId, uri, title).firstOrNull { it.isNotBlank() } ?: return null
        val name = title.ifBlank {
            listOf(id, reporterId, uri).firstOrNull { it.isNotBlank() } ?: key
        }
        return HomeRecommendTabEntry(
            order = index,
            key = key,
            name = name,
            uri = uri,
            reporterId = reporterId,
            index = index,
        )
    }

    private fun Any.readString(field: Field?): String =
        field?.let {
            runCatching { it.get(this) as? String }
                .getOrNull()
                ?.trim()
                .orEmpty()
        }.orEmpty()

    private fun saveKnownTabs(entries: Collection<HomeRecommendTabEntry>) {
        if (entries.isEmpty()) return
        val encoded = entries
            .distinctBy { it.key }
            .map { entry ->
                encodeTab(
                    order = entry.order,
                    key = entry.key,
                    name = entry.name,
                    uri = entry.uri,
                    reporterId = entry.reporterId,
                )
            }
            .toMutableSet()
        val oldItems = ModuleSettings.getKnownHomeRecommendTabs(prefs)
        if (oldItems == encoded) return
        ModuleSettings.cacheKnownHomeRecommendTabs(encoded)
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_KNOWN_HOME_RECOMMEND_TABS, encoded)
            .apply()
    }

    private fun encodeTab(
        order: Int,
        key: String,
        name: String,
        uri: String,
        reporterId: String,
    ): String =
        listOf(order.toString(), key, name, uri, reporterId)
            .joinToString(ITEM_SEPARATOR) { it.sanitizeItemPart() }

    private fun String.sanitizeItemPart(): String =
        replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')

    private data class HomeRecommendTabEntry(
        val order: Int,
        val key: String,
        val name: String,
        val uri: String,
        val reporterId: String,
        val index: Int,
    )

    private companion object {
        private const val ITEM_SEPARATOR = "\t"
        private val POSITION_FIELD_NAMES = setOf("pos", "position", "order")
        private val POSITION_FIELD_TYPES = setOf(
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Long::class.javaPrimitiveType,
            Long::class.javaObjectType,
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType,
        )
    }
}
