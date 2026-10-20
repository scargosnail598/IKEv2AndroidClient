package com.saeed.ikev2vpn.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BypassViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private class Store(var saved: BypassConfig = BypassConfig()) : BypassRepository {
        var writes = 0
        override fun load() = saved
        override fun save(config: BypassConfig) { writes++; saved = config }
    }
    private fun model(store: Store, source: CountryRouteSource = CountryRouteSource { error("No network expected") }) =
        BypassViewModel(store, source, dispatcher, dispatcher)
    @Test fun `edits stay in draft until explicit save and retain independent country and suffix choices`() = runTest {
        val store = Store(); val model = model(store)
        model.enableDomains(true); model.updateSuffixes(".IR .ru")
        assertEquals(0, store.writes)
        model.save()
        assertEquals(setOf("ir", "ru"), store.saved.suffixes)
        assertTrue(store.saved.domainsEnabled); assertFalse(store.saved.countriesEnabled)
        assertFalse(model.state.value.dirty)
        assertTrue(model.state.value.message!!.contains("next connection"))
    }
    @Test fun `malformed suffixes cannot replace saved rules`() = runTest {
        val store = Store(BypassConfig(true, setOf("ir")))
        val model = model(store); model.updateSuffixes("https://example.ir"); model.save()
        assertEquals(0, store.writes)
        assertEquals(setOf("ir"), store.saved.suffixes)
        assertNotNull(model.state.value.error)
    }
    @Test fun `failed country updates keep the old country routes`() = runTest {
        val country = CountryRoutes("IR", listOf(IpSubnet.parse("5.160.0.0/16")), 1)
        val store = Store(BypassConfig(countriesEnabled = true, countries = listOf(country)))
        val model = model(store, CountryRouteSource { throw java.io.IOException("offline") })
        model.downloadCountry("IR")
        assertEquals(listOf(country), model.state.value.config.countries)
        assertEquals(0, store.writes)
        assertNotNull(model.state.value.error)
    }
    @Test fun `country data downloads once then saves and works without further network access`() = runTest {
        var downloads = 0
        val store = Store()
        val model = model(store, CountryRouteSource { downloads++; CountryRoutes(it, listOf(IpSubnet.parse("5.160.0.0/16")), System.currentTimeMillis()) })
        model.enableCountries(true); model.downloadCountry("IR")
        assertEquals(1, downloads); assertEquals(0, store.writes)
        model.save(); model.downloadCountry("IR")
        assertEquals(1, downloads); assertEquals("IR", store.saved.countries.single().code)
        assertEquals(store.saved, model(store).state.value.config)
    }
    @Test fun `removing a country is a draft change and disabling rules does not delete downloaded lists`() = runTest {
        val country = CountryRoutes("IR", listOf(IpSubnet.parse("5.160.0.0/16")), 1)
        val store = Store(BypassConfig(countriesEnabled = true, countries = listOf(country)))
        val model = model(store)
        model.enableCountries(false); model.save()
        assertEquals(listOf(country), store.saved.countries)
        model.removeCountry("IR")
        assertEquals(listOf(country), store.saved.countries)
        assertTrue(model.state.value.config.countries.isEmpty())
    }
}
