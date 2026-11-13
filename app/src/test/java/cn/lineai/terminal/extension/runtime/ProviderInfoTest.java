package cn.lineai.terminal.extension.runtime;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ProviderInfoTest {

    @Test
    public void describesTheUbuntuGuestForLineCode() {
        String json = ProviderInfo.json("/root/workspace");
        assertTrue(json.contains("\"name\":\"LineCode Terminal Extension\""));
        assertTrue(json.contains("\"version\":\"1.0.0\""));
        assertTrue(json.contains("\"shell\":\"/bin/bash\""));
        assertTrue(json.contains("\"home\":\"/root/workspace\""));
        assertTrue(json.contains("\"arch\":\"aarch64\""));
        assertTrue(json.contains("\"executeShell\""));
        assertTrue(json.contains("\"readFileChunk\""));
        assertTrue(json.contains("\"listDirDetailed\""));
    }

    @Test
    public void escapesQuotesInTheHomePath() {
        String json = ProviderInfo.json("/root/\"quoted\"");
        assertTrue(json.contains("\\\"quoted\\\""));
    }
}
