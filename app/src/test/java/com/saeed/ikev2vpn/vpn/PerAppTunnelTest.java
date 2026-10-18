package com.saeed.ikev2vpn.vpn;

import android.content.pm.PackageManager;
import android.net.VpnService;
import org.junit.Test;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.logic.CharonVpnService;
import java.util.Arrays;
import java.util.TreeSet;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class PerAppTunnelTest {
    private CharonVpnService.BuilderCache cache(VpnProfile.SelectedAppsHandling mode, String... packages) {
        CharonVpnService service = mock(CharonVpnService.class);
        when(service.getPackageName()).thenReturn("com.saeed.ikev2vpn");
        VpnProfile profile = mock(VpnProfile.class);
        when(profile.getSelectedAppsHandling()).thenReturn(mode);
        when(profile.getSelectedAppsSet()).thenReturn(new TreeSet<>(Arrays.asList(packages)));
        return service.new BuilderCache(profile);
    }

    @Test
    public void emptyIncludeListFailsBeforeEstablishingTunnel() {
        VpnService.Builder builder = mock(VpnService.Builder.class);
        assertThrows(IllegalArgumentException.class, () ->
            cache(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY).applyData(builder));
    }

    @Test
    public void ownAppOnlyFailsAfterEngineExcludesIt() {
        VpnService.Builder builder = mock(VpnService.Builder.class);
        assertThrows(IllegalArgumentException.class, () ->
            cache(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY, "com.saeed.ikev2vpn").applyData(builder));
    }

    @Test
    public void allSelectedAppsUninstalledFailsInsteadOfRoutingAllApps() throws Exception {
        VpnService.Builder builder = mock(VpnService.Builder.class);
        when(builder.addAllowedApplication(anyString())).thenThrow(new PackageManager.NameNotFoundException());
        assertThrows(IllegalArgumentException.class, () ->
            cache(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY, "com.example.deleted").applyData(builder));
    }

    @Test
    public void oneInstalledAppKeepsIncludeRoutingWhenAnotherIsUninstalled() throws Exception {
        VpnService.Builder builder = mock(VpnService.Builder.class);
        when(builder.addAllowedApplication("com.example.deleted")).thenThrow(new PackageManager.NameNotFoundException());
        cache(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY,
            "com.example.deleted", "com.example.browser").applyData(builder);
        verify(builder).addAllowedApplication("com.example.browser");
        verify(builder, never()).addDisallowedApplication(anyString());
    }

    @Test
    public void exclusionRoutesAllOtherAppsAndKeepsEngineAppExcluded() throws Exception {
        VpnService.Builder builder = mock(VpnService.Builder.class);
        cache(VpnProfile.SelectedAppsHandling.SELECTED_APPS_EXCLUDE, "com.example.browser").applyData(builder);
        verify(builder).addDisallowedApplication("com.example.browser");
        verify(builder).addDisallowedApplication("com.saeed.ikev2vpn");
        verify(builder, never()).addAllowedApplication(anyString());
    }
}
