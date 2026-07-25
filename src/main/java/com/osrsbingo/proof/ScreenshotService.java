package com.osrsbingo.proof;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;

/**
 * Captures a frame as proof, downscales it, writes a JPEG to disk, and hands
 * back the file.
 *
 * <p>JPEG rather than PNG or WebP: a game frame is photographic, so PNG runs
 * 1-2 MB where JPEG lands at ~200-300 KB, and Java 11's ImageIO has no WebP
 * writer (adding one needs a native library, which the Plugin Hub forbids).
 *
 * <p>Capture must never block a report: if no frame arrives within
 * {@link #CAPTURE_TIMEOUT_MS} the callback fires with null and the drop is
 * submitted without proof.
 */
@Slf4j
public class ScreenshotService
{
	private static final int MAX_WIDTH = 1280;
	private static final float JPEG_QUALITY = 0.85f;
	private static final long CAPTURE_TIMEOUT_MS = 2_000L;

	private static final DateTimeFormatter STAMP =
		DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	@Inject
	public ScreenshotService(DrawManager drawManager, ScheduledExecutorService executor)
	{
		this.drawManager = drawManager;
		this.executor = executor;
	}

	/**
	 * @param itemName used in the filename; must not be a board code
	 * @param callback invoked exactly once, with null when no proof is available
	 */
	public void capture(String itemName, Consumer<File> callback)
	{
		AtomicBoolean done = new AtomicBoolean();

		try
		{
			executor.schedule(() ->
			{
				if (done.compareAndSet(false, true))
				{
					log.debug("Bingo screenshot timed out; submitting without proof");
					callback.accept(null);
				}
			}, CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
		catch (RuntimeException e)
		{
			// The timeout could not be armed (e.g. the executor is shutting
			// down during plugin teardown). Registering a frame listener now
			// would risk never firing the callback if no frame ever arrives,
			// so fail fast instead of leaving the drop stranded.
			if (done.compareAndSet(false, true))
			{
				log.debug("Bingo screenshot timeout could not be armed", e);
				callback.accept(null);
			}
			return;
		}

		try
		{
			drawManager.requestNextFrameListener(image ->
			{
				try
				{
					executor.submit(() ->
					{
						if (!done.compareAndSet(false, true))
						{
							return;
						}
						callback.accept(writeJpeg(image, itemName));
					});
				}
				catch (RuntimeException e)
				{
					if (done.compareAndSet(false, true))
					{
						log.debug("Bingo screenshot encoding could not be submitted", e);
						callback.accept(null);
					}
				}
			});
		}
		catch (RuntimeException e)
		{
			if (done.compareAndSet(false, true))
			{
				log.debug("Bingo screenshot request failed", e);
				callback.accept(null);
			}
		}
	}

	private File writeJpeg(Image image, String itemName)
	{
		try
		{
			BufferedImage source = ImageUtil.bufferedImageFromImage(image);
			BufferedImage scaled = downscale(source);
			File target = targetFile(itemName);
			writeJpeg(scaled, target);
			return target;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Failed to write bingo proof screenshot", e);
			return null;
		}
	}

	private static BufferedImage downscale(BufferedImage source)
	{
		if (source.getWidth() <= MAX_WIDTH)
		{
			return toRgb(source);
		}
		int width = MAX_WIDTH;
		int height = (int) Math.round(source.getHeight() * (MAX_WIDTH / (double) source.getWidth()));
		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(source, 0, 0, width, height, null);
		g.dispose();
		return target;
	}

	/** JPEG has no alpha channel; an ARGB source must be flattened first. */
	private static BufferedImage toRgb(BufferedImage source)
	{
		if (source.getType() == BufferedImage.TYPE_INT_RGB)
		{
			return source;
		}
		BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(),
			BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return target;
	}

	private static void writeJpeg(BufferedImage image, File target) throws IOException
	{
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext())
		{
			throw new IOException("No JPEG writer available");
		}
		ImageWriter writer = writers.next();
		try (FileImageOutputStream out = new FileImageOutputStream(target))
		{
			writer.setOutput(out);
			ImageWriteParam params = writer.getDefaultWriteParam();
			if (params.canWriteCompressed())
			{
				params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				params.setCompressionQuality(JPEG_QUALITY);
			}
			writer.write(null, new IIOImage(image, null, null), params);
		}
		finally
		{
			writer.dispose();
		}
	}

	private static File targetFile(String itemName)
	{
		File dir = new File(RuneLite.RUNELITE_DIR, "screenshots" + File.separator + "osrs-bingo");
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create bingo screenshot directory");
		}
		String safeName = itemName == null ? "drop" : itemName.replaceAll("[^A-Za-z0-9]+", "-");
		return new File(dir, LocalDateTime.now().format(STAMP) + "_" + safeName + ".jpg");
	}
}
