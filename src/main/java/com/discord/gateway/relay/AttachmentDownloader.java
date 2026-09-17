package com.discord.gateway.relay;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface AttachmentDownloader {
    InputStream download(String url) throws IOException;
}
