package net.clgd.ccemux.emulation;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import dan200.computercraft.ComputerCraft;
import dan200.computercraft.api.filesystem.IWritableMount;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.computer.Computer;
import net.clgd.ccemux.Utils;
import net.clgd.ccemux.api.emulation.EmulatedComputer;
import net.clgd.ccemux.api.emulation.EmulatedTerminal;
import net.clgd.ccemux.rendering.awt.AWTTerminalFont;
import net.clgd.ccemux.rendering.awt.TerminalRenderer;

/**
 * Represents a computer that can be emulated via CCEmuX
 */
public class EmulatedComputerImpl extends EmulatedComputer {
	private static final Logger log = LoggerFactory.getLogger(EmulatedComputerImpl.class);
	private static final ListeningExecutorService SCREENSHOTS = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

	private static final BiConsumer<EmulatedComputer, IWritableMount> setRootMount;

	static {
		setRootMount = createSetter();
	}

	private static BiConsumer<EmulatedComputer, IWritableMount> createSetter() {
		List<Exception> errors = new ArrayList<>();
		try {
			Field mountField = Computer.class.getDeclaredField("m_rootMount");
			mountField.setAccessible(true);
			return (computer, mount) -> {
				try {
					mountField.set(computer, mount);
				} catch (IllegalArgumentException | IllegalAccessException e) {
					throw new RuntimeException("Failed to set root mount while building computer ID " + computer.getID(), e);
				}
			};
		} catch (NoSuchFieldException | SecurityException e) {
			errors.add(e);
		}

		try {
			Field executorField = Computer.class.getDeclaredField("executor");
			executorField.setAccessible(true);

			Field mountField = executorField.getType().getDeclaredField("rootMount");
			mountField.setAccessible(true);
			return (computer, mount) -> {
				try {
					mountField.set(executorField.get(computer), mount);
				} catch (IllegalArgumentException | IllegalAccessException e) {
					throw new RuntimeException("Failed to set root mount while building computer ID " + computer.getID(), e);
				}
			};
		} catch (NoSuchFieldException | SecurityException e) {
			errors.add(e);
		}

		if (errors.isEmpty()) {
			log.error("Failed to get computer root mount field");
		} else {
			Exception error = errors.get(0);
			for (int i = 1; i < errors.size(); i++) error.addSuppressed(errors.get(i));
			log.error("Failed to get computer root mount field", error);
		}

		return null;
	}

	/**
	 * A class used to create new {@link EmulatedComputer} instances
	 *
	 * @author apemanzilla
	 */
	public static class BuilderImpl implements Builder {
		private final CCEmuX emu;
		private final EmulatedTerminal term;

		private Integer id = null;

		private IWritableMount rootMount = null;

		private String label = null;

		private transient AtomicBoolean built = new AtomicBoolean();

		private BuilderImpl(CCEmuX emu, EmulatedTerminal term) {
			this.emu = emu;
			this.term = term;
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see net.clgd.ccemux.emulation.Builder#id(java.lang.Integer)
		 */
		@Nonnull
		@Override
		public BuilderImpl id(Integer num) {
			id = num;
			return this;
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see net.clgd.ccemux.emulation.Builder#rootMount(dan200.computercraft.api.
		 * filesystem.IWritableMount)
		 */
		@Nonnull
		@Override
		public Builder rootMount(IWritableMount rootMount) {
			this.rootMount = rootMount;
			return this;
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see net.clgd.ccemux.emulation.Builder#label(java.lang.String)
		 */
		@Nonnull
		@Override
		public Builder label(String label) {
			this.label = label;
			return this;
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see net.clgd.ccemux.emulation.Builder#build()
		 */
		@Nonnull
		@Override
		public EmulatedComputer build() {
			if (built.getAndSet(true)) throw new IllegalStateException("This computer has already been built!");

			EmulatedComputer ec = new EmulatedComputerImpl(emu, term, Optional.ofNullable(id).orElse(-1));
			ec.assignID();

			if (label != null) ec.setLabel(label);

			if (setRootMount == null) {
				throw new RuntimeException("Failed to set root mount while building computer ID " + ec.getID() + ". No mount setter available.");
			}

			if (rootMount != null) {
				setRootMount.accept(ec, rootMount);
			} else {
				setRootMount.accept(ec, emu.createSaveDirMount(Integer.toString(ec.getID()), ComputerCraft.computerSpaceLimit));
			}

			return ec;
		}
	}

	/**
	 * Gets a new builder to create an {@link EmulatedComputer} instance.
	 *
	 * @return The new builder
	 */
	public static Builder builder(CCEmuX emu, EmulatedTerminal term) {
		return new BuilderImpl(emu, term);
	}

	/**
	 * The emulator this computer belongs to
	 */
	private final CCEmuX emulator;

	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

	private EmulatedComputerImpl(CCEmuX emulator, EmulatedTerminal terminal, int id) {
		super(emulator, terminal, id);
		this.emulator = emulator;
	}

	@Override
	public boolean addListener(@Nonnull Listener l) {
		return listeners.add(l);
	}

	@Override
	public boolean removeListener(@Nonnull Listener l) {
		return listeners.remove(l);
	}

	@Override
	public void advance(double dt) {
		super.advance(dt);

		for (int i = 0; i < 6; i++) {
			IPeripheral peripheral = getPeripheral(i);
			if (peripheral instanceof Listener) ((Listener) peripheral).onAdvance(dt);
		}

		listeners.forEach(l -> l.onAdvance(dt));
	}

	@Override
	@SuppressWarnings("deprecation")
	public void copyFiles(@Nonnull Iterable<File> files, @Nonnull String location) throws IOException {
		IWritableMount mount = this.getRootMount();
		Path base = Paths.get(location);

		for (File f : files) {
			String path = base.resolve(f.getName()).toString();

			if (f.isFile()) {
				if (f.length() > mount.getRemainingSpace()) {
					throw new IOException("Not enough space on computer");
				}

				try (OutputStream s = mount.openForWrite(path); InputStream o = new FileInputStream(f)) {
					ByteStreams.copy(o, s);
				}
			} else {
				mount.makeDirectory(path);
				copyFiles(Arrays.asList(f.listFiles()), path);
			}
		}
	}

	@Nonnull
	@Override
	public ListenableFuture<File> screenshot() {
		return SCREENSHOTS.submit(() -> {
			Path screenshotDir = emulator.getConfig().getDataDir().resolve("screenshots");
			Files.createDirectories(screenshotDir);

			LocalDateTime instant = LocalDateTime.now();
			File file = Utils.createUniqueFile(screenshotDir.toFile(), String.format("%04d-%02d-%02d-%02d_%02d_%02d",
				instant.get(ChronoField.YEAR), instant.get(ChronoField.MONTH_OF_YEAR), instant.get(ChronoField.DAY_OF_MONTH),
				instant.get(ChronoField.HOUR_OF_DAY), instant.get(ChronoField.MINUTE_OF_HOUR), instant.get(ChronoField.SECOND_OF_MINUTE)
			), ".png");

			AWTTerminalFont font = AWTTerminalFont.getBest(AWTTerminalFont::new);
			TerminalRenderer renderer = new TerminalRenderer(terminal, emulator.getConfig().termScale.get());
			try {
				synchronized (terminal) {
					Dimension dimension = renderer.getSize();
					BufferedImage image = new BufferedImage(dimension.width, dimension.height, BufferedImage.TYPE_3BYTE_BGR);

					Graphics graphics = image.getGraphics();
					renderer.render(font, graphics);
					graphics.dispose();

					ImageIO.write(image, "png", file);
				}

				log.info("Saved screenshot to {}", file.getAbsolutePath());

				return file;
			} catch (IOException e) {
				file.delete();
				throw e;
			}
		});
	}

	@Override
	public void setLabel(String label) {
		if (!Objects.equal(label, getLabel())) {
			super.setLabel(label);
			emulator.sessionStateChanged();
		}
	}
}
