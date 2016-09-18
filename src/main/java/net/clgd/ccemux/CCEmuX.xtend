package net.clgd.ccemux

import dan200.computercraft.ComputerCraft
import java.awt.SplashScreen
import java.nio.file.Paths
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import org.eclipse.xtend.lib.annotations.Accessors
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static extension net.clgd.ccemux.Utils.*
import java.nio.file.Files
import org.apache.commons.cli.HelpFormatter
import java.io.File
import java.nio.file.Path
import dan200.computercraft.core.computer.ComputerThread

class CCEmuX {
	@Accessors(PUBLIC_GETTER) static Logger logger
	@Accessors(PUBLIC_GETTER) static EmulatorWindow window
	@Accessors(PUBLIC_GETTER) static Config conf
	@Accessors(PUBLIC_GETTER) static var portable = false
	@Accessors(PUBLIC_GETTER) static Path dataDir
	
	def static void main(String[] args) {
		val opts = new Options().using [
			buildOpt("h") [
				longOpt("help")
				desc("Shows the help information")
			]

			buildOpt("p") [
				longOpt("portable")

				desc("Forces portable mode, in which all files (configs, saves, libraries) are kept in the same folder as CCEmuX." +
					"Will automatically be enabled if the config file is in the same folder as CCEmuX.")
			]
			
			buildOpt("d") [
				longOpt("data-dir")
				
				desc("Manually sets the data directory. Overrides portable flag.")
				hasArg()
				argName("path")
			]
			
			buildOpt("l") [
				longOpt("log-level")
				
				desc("Manually specify the logging level. Valid options are 'trace', 'debug', 'info', 'warning', and 'error', in that order from most verbose to least.")
				hasArg()
				argName("level")
			]
		]

		val cmd = new DefaultParser().parse(opts, args)

		if (cmd.hasOption('h')) {
			val hf = new HelpFormatter
			hf.printHelp("java -jar " + new File(CCEmuX.getProtectionDomain.codeSource.location.toURI).name + " <args>", opts)
			System.exit(1)
		}

		portable = cmd.hasOption('p')

		cmd.getOptionValue("l")?.trim.using [
			if (#{"trace", "debug", "info", "warning", "error"}.contains(it))
				System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", it)
			else
				System.err.println("Invalid logging level: " + it)
		]

		logger = LoggerFactory.getLogger("CCEmuX")
		
		logger.info("Starting CCEmuX...")
		
		dataDir = if(cmd.hasOption('d')) Paths.get(cmd.getOptionValue('d')) else if(portable) Paths.get("") else OperatingSystem.get.appDataDir.resolve("ccemux")
		logger.debug("Data directory is {}", dataDir.toAbsolutePath.toString)
		Files.createDirectories(dataDir)
		
		logger.debug("Loading configuration data...", dataDir.resolve(Config.CONFIG_FILE_NAME).toString)
		conf = new Config(dataDir.resolve(Config.CONFIG_FILE_NAME).toFile)
		logger.debug("Configuration data loaded.")

		CCBootstrapper.loadCC
		logger.info("Loaded CC (v{})", ComputerCraft.version)
		
		if (ComputerCraft.version != conf.CCRevision)
			logger.warn("Potential compatibility issues detected - expected CC version (v{}) does not match loaded CC version (v{})", conf.CCRevision, ComputerCraft.version)
		
		ComputerThread.start
		
		window = new EmulatorWindow().using [
			// close splash screen before making frame visible
			// prevents occasional window focus changes
			SplashScreen.splashScreen?.close
			
			visible = true
		]
	}
}
		