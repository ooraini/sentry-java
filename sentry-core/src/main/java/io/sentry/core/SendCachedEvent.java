package io.sentry.core;

import static io.sentry.core.ILogger.logIfNotNull;

import io.sentry.core.cache.DiskCache;
import io.sentry.core.util.Objects;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import org.jetbrains.annotations.NotNull;

final class SendCachedEvent {
  private static final Charset UTF_8 = Charset.forName("UTF-8");
  private final ISerializer serializer;
  private final IHub hub;
  private final ILogger logger;

  SendCachedEvent(@NotNull ISerializer serializer, @NotNull IHub hub, @NotNull ILogger logger) {
    this.serializer = Objects.requireNonNull(serializer, "Serializer is required.");
    this.hub = Objects.requireNonNull(hub, "Hub is required.");
    this.logger = Objects.requireNonNull(logger, "Logger is required.");
  }

  public void sendCachedFiles(@NotNull File directory) {
    if (!directory.exists()) {
      logIfNotNull(
          logger,
          SentryLevel.WARNING,
          "Directory '%s' doesn't exist. No cached events to send.",
          directory.getAbsolutePath());
      return;
    }
    if (!directory.isDirectory()) {
      logIfNotNull(
          logger,
          SentryLevel.ERROR,
          "Cache dir %s is not a directory.",
          directory.getAbsolutePath());
      return;
    }

    logIfNotNull(
        logger,
        SentryLevel.DEBUG,
        "Processing %d items from cache dir %s",
        directory.length(),
        directory.getAbsolutePath());

    for (File file : directory.listFiles()) {
      if (!file.getName().endsWith(DiskCache.FILE_SUFFIX)) {
        logIfNotNull(
            logger,
            SentryLevel.DEBUG,
            "File '%s' doesn't match extension expected.",
            file.getName());
        continue;
      }

      if (!file.isFile()) {
        logIfNotNull(logger, SentryLevel.DEBUG, "'%s' is not a file.", file.getAbsolutePath());
        continue;
      }

      if (!file.getParentFile().canWrite()) {
        logIfNotNull(
            logger,
            SentryLevel.WARNING,
            "File '%s' cannot be delete so it will not be processed.",
            file.getName());
        continue;
      }

      CachedEvent hint = new CachedEvent();
      try (Reader reader =
          new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF_8))) {
        SentryEvent event = serializer.deserializeEvent(reader);
        hub.captureEvent(event, hint);
      } catch (FileNotFoundException e) {
        logIfNotNull(logger, SentryLevel.ERROR, "File '%s' cannot be found.", file.getName(), e);
      } catch (IOException e) {
        logIfNotNull(logger, SentryLevel.ERROR, "I/O on file '%s' failed.", file.getName(), e);
      } catch (Exception e) {
        logIfNotNull(
            logger, SentryLevel.ERROR, "Failed to capture cached event.", file.getName(), e);
        hint.setResend(false);
      } finally {
        // Unless the transport marked this to be retried, it'll be deleted.
        if (!hint.isResend()) {
          safeDelete(file, "after trying to capture it");
        }
      }
    }
  }

  private void safeDelete(File file, String errorMessageSuffix) {
    try {
      file.delete();
    } catch (Exception e) {
      logIfNotNull(
          logger,
          SentryLevel.ERROR,
          "Failed to delete '%s' " + errorMessageSuffix,
          file.getName(),
          e);
    }
  }
}