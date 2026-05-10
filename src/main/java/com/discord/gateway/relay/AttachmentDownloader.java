package com.discord.gateway.relay;

import java.io.IOException;

@FunctionalInterface
public interface AttachmentDownloader {
    byte[] download(String url) throws IOException;
}
