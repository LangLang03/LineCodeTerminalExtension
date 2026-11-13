package cn.lineai.terminal.extension.runtime;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RootfsOverlayTest {

    @Test
    public void resolvConfDropsIpv6Resolvers() {
        String conf = RootfsOverlay.resolvConf(Arrays.asList(
                "fec0::3", "10.0.2.3", "2001:db8::1", "1.1.1.1"));
        assertTrue(conf.contains("nameserver 10.0.2.3"));
        assertTrue(conf.contains("nameserver 1.1.1.1"));
        assertFalse(conf.contains("fec0"));
        assertFalse(conf.contains("2001"));
    }

    @Test
    public void resolvConfFallsBackToIpv4Resolvers() {
        String conf = RootfsOverlay.resolvConf(Arrays.asList("fec0::3"));
        assertTrue(conf.contains("nameserver 223.5.5.5"));
        assertFalse(conf.contains("fec0"));
    }
}
