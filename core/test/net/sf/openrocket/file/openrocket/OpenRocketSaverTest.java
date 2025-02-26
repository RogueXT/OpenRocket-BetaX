package net.sf.openrocket.file.openrocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import net.sf.openrocket.ServicesForTesting;
import net.sf.openrocket.database.ComponentPresetDao;
import net.sf.openrocket.database.ComponentPresetDatabase;
import net.sf.openrocket.database.motor.MotorDatabase;
import net.sf.openrocket.database.motor.ThrustCurveMotorSetDatabase;
import net.sf.openrocket.document.OpenRocketDocument;
import net.sf.openrocket.document.OpenRocketDocumentFactory;
import net.sf.openrocket.document.Simulation;
import net.sf.openrocket.document.StorageOptions;
import net.sf.openrocket.file.GeneralRocketLoader;
import net.sf.openrocket.file.RocketLoadException;
import net.sf.openrocket.file.motor.GeneralMotorLoader;
import net.sf.openrocket.l10n.DebugTranslator;
import net.sf.openrocket.l10n.Translator;
import net.sf.openrocket.logging.ErrorSet;
import net.sf.openrocket.logging.WarningSet;
import net.sf.openrocket.motor.Manufacturer;
import net.sf.openrocket.motor.Motor;
import net.sf.openrocket.motor.ThrustCurveMotor;
import net.sf.openrocket.plugin.PluginModule;
import net.sf.openrocket.rocketcomponent.BodyTube;
import net.sf.openrocket.rocketcomponent.FlightConfiguration;
import net.sf.openrocket.rocketcomponent.FlightConfigurationId;
import net.sf.openrocket.rocketcomponent.Rocket;
import net.sf.openrocket.simulation.extension.impl.ScriptingExtension;
import net.sf.openrocket.simulation.extension.impl.ScriptingUtil;
import net.sf.openrocket.startup.Application;
import net.sf.openrocket.util.Coordinate;
import net.sf.openrocket.util.TestRockets;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.util.Modules;

public class OpenRocketSaverTest {
	
	private final OpenRocketSaver saver = new OpenRocketSaver();
	private static final File TMP_DIR = new File("./tmp/");
	
	public static final String SIMULATION_EXTENSION_SCRIPT = "// Test <  &\n// >\n// <![CDATA[";
	
	private static Injector injector;
	
	@BeforeClass
	public static void setup() {
		Module applicationModule = new ServicesForTesting();
		Module pluginModule = new PluginModule();
		
		Module dbOverrides = new AbstractModule() {
			@Override
			protected void configure() {
				bind(ComponentPresetDao.class).toProvider(new EmptyComponentDbProvider());
				bind(MotorDatabase.class).toProvider(new MotorDbProvider());
				bind(Translator.class).toInstance(new DebugTranslator(null));
			}
		};
		
		injector = Guice.createInjector(Modules.override(applicationModule).with(dbOverrides), pluginModule);
		Application.setInjector(injector);
		
		if( !(TMP_DIR.exists() && TMP_DIR.isDirectory()) ){
			boolean success = TMP_DIR.mkdirs();
			if (!success) {
				fail("Unable to create core/tmp dir needed for tests.");
			}
		}
	}
	
	@After
	public void deleteRocketFilesFromTemp() {
		final String fileNameMatchStr = String.format("%s_.*\\.ork", this.getClass().getName());
		
		File[] toBeDeleted = TMP_DIR.listFiles(new FileFilter() {
			@Override
			public boolean accept(File theFile) {
				if (theFile.isFile()) {
					return theFile.getName().matches(fileNameMatchStr);
				}
				return false;
			}
		});
		
		for (File deletableFile : toBeDeleted) {
			deletableFile.delete();
		}
	}
	
	/**
	 * Test for creating, saving, and loading various rockets with different file versions
	 * 
	 * TODO: add a deep equality check to ensure no changes after save/read
	 */
	
	@Test
	public void testCreateLoadSave() {
		
		// Create rockets
		ArrayList<OpenRocketDocument> rocketDocs = new ArrayList<OpenRocketDocument>();
		rocketDocs.add(TestRockets.makeTestRocket_v100());
		rocketDocs.add(TestRockets.makeTestRocket_v101_withFinTabs());
		rocketDocs.add(TestRockets.makeTestRocket_v101_withTubeCouplerChild());
		// no version 1.2 file type exists
		// no version 1.3 file type exists
		rocketDocs.add(TestRockets.makeTestRocket_v104_withSimulationData());
		rocketDocs.add(TestRockets.makeTestRocket_v104_withMotor());
		rocketDocs.add(TestRockets.makeTestRocket_v105_withComponentPreset());
		rocketDocs.add(TestRockets.makeTestRocket_v105_withCustomExpression());
		rocketDocs.add(TestRockets.makeTestRocket_v105_withLowerStageRecoveryDevice());
		rocketDocs.add(TestRockets.makeTestRocket_v106_withAppearance());
		rocketDocs.add(TestRockets.makeTestRocket_v106_withMotorMountIgnitionConfig());
		rocketDocs.add(TestRockets.makeTestRocket_v106_withRecoveryDeviceDeploymentConfig());
		rocketDocs.add(TestRockets.makeTestRocket_v106_withStageSeparationConfig());
		rocketDocs.add(TestRockets.makeTestRocket_v107_withSimulationExtension(SIMULATION_EXTENSION_SCRIPT));
        rocketDocs.add(TestRockets.makeTestRocket_v108_withBoosters());
		rocketDocs.add(TestRockets.makeTestRocket_v108_withDisabledStage());
		rocketDocs.add(TestRockets.makeTestRocket_for_estimateFileSize());
		
		StorageOptions options = new StorageOptions();
		options.setSaveSimulationData(true);
		
		// Save rockets, load, validate
		for (OpenRocketDocument rocketDoc : rocketDocs) {
			File file = saveRocket(rocketDoc, options);
			OpenRocketDocument rocketDocLoaded = loadRocket(file.getPath());
			assertNotNull(rocketDocLoaded);
		}
	}

	@Test
	public void testSaveStageActiveness() {
		OpenRocketDocument rocketDoc = TestRockets.makeTestRocket_v108_withDisabledStage();
		StorageOptions options = new StorageOptions();
		options.setSaveSimulationData(true);

		// Save rockets, load, validate
		File file = saveRocket(rocketDoc, options);
		OpenRocketDocument rocketDocLoaded = loadRocket(file.getPath());

		// Check that the stages activeness is saved
		FlightConfiguration config = rocketDocLoaded.getRocket().getSelectedConfiguration();
		assertFalse(" selected config, stage 0 should have been disabled after saving", config.isStageActive(0));
		assertTrue(" selected config, stage 1 should have been enabled after saving", config.isStageActive(1));
		assertTrue(" selected config, stage 2 should have been enabled after saving", config.isStageActive(2));

		// Disable second stage
		config._setStageActive(1, false, false);
		file = saveRocket(rocketDocLoaded, options);
		rocketDocLoaded = loadRocket(file.getPath());
		config = rocketDocLoaded.getRocket().getSelectedConfiguration();
		assertFalse(" selected config, stage 0 should have been disabled after saving", config.isStageActive(0));
		assertFalse(" selected config, stage 1 should have been disabled after saving", config.isStageActive(1));
		assertTrue(" selected config, stage 2 should have been enabled after saving", config.isStageActive(2));

		// Re-enable first stage
		config._setStageActive(0, true, false);
		file = saveRocket(rocketDocLoaded, options);
		rocketDocLoaded = loadRocket(file.getPath());
		config = rocketDocLoaded.getRocket().getSelectedConfiguration();
		assertTrue(" selected config, stage 0 should have been enabled after saving", config.isStageActive(0));
		assertFalse(" selected config, stage 1 should have been disabled after saving", config.isStageActive(1));
		assertTrue(" selected config, stage 2 should have been enabled after saving", config.isStageActive(2));

		// Check that other configurations are not affected
		FlightConfiguration extraConfig = rocketDocLoaded.getRocket().createFlightConfiguration(TestRockets.TEST_FCID_0);
		extraConfig.setAllStages();
		file = saveRocket(rocketDocLoaded, options);
		rocketDocLoaded = loadRocket(file.getPath());
		config = rocketDocLoaded.getRocket().getSelectedConfiguration();
		extraConfig = rocketDocLoaded.getRocket().getFlightConfiguration(TestRockets.TEST_FCID_0);
		assertTrue(" selected config, stage 0 should have been enabled after saving", config.isStageActive(0));
		assertFalse(" selected config, stage 1 should have been disabled after saving", config.isStageActive(1));
		assertTrue(" selected config, stage 2 should have been enabled after saving", config.isStageActive(2));
		assertTrue(" extra config, stage 0 should have been enabled after saving", extraConfig.isStageActive(0));
		assertTrue(" extra config, stage 1 should have been enabled after saving", extraConfig.isStageActive(1));
		assertTrue(" extra config, stage 2 should have been enabled after saving", extraConfig.isStageActive(2));

		// Disable a stage in the extra config, and an extra one in the selected config
		extraConfig._setStageActive(0, false, false);
		config._setStageActive(2, false, false);
		file = saveRocket(rocketDocLoaded, options);
		rocketDocLoaded = loadRocket(file.getPath());
		config = rocketDocLoaded.getRocket().getSelectedConfiguration();
		extraConfig = rocketDocLoaded.getRocket().getFlightConfiguration(TestRockets.TEST_FCID_0);
		assertTrue(" selected config, stage 0 should have been enabled after saving", config.isStageActive(0));
		assertFalse(" selected config, stage 1 should have been disabled after saving", config.isStageActive(1));
		assertFalse(" selected config, stage 2 should have been disabled after saving", config.isStageActive(2));
		assertFalse(" extra config, stage 0 should have been disabled after saving", extraConfig.isStageActive(0));
		assertTrue(" extra config, stage 1 should have been enabled after saving", extraConfig.isStageActive(1));
		assertTrue(" extra config, stage 2 should have been enabled after saving", extraConfig.isStageActive(2));

		// Test an empty rocket with no configurations
		OpenRocketDocument document = OpenRocketDocumentFactory.createNewRocket();
		file = saveRocket(document, options);
		rocketDocLoaded = loadRocket(file.getPath());
		rocketDocLoaded.getRocket().getStage(0).addChild(new BodyTube());		// Add a child, otherwise the stage is always marked inactive
		config = rocketDocLoaded.getRocket().getSelectedConfiguration();
		assertTrue(" empty rocket, selected config, stage 0 should have been enabled after saving", config.isStageActive(0));
	}
	
	@Test
	public void testUntrustedScriptDisabledOnLoad() {
		OpenRocketDocument rocketDoc = TestRockets.makeTestRocket_v107_withSimulationExtension(SIMULATION_EXTENSION_SCRIPT);
		StorageOptions options = new StorageOptions();
		File file = saveRocket(rocketDoc, options);
		OpenRocketDocument rocketDocLoaded = loadRocket(file.getPath());
		assertEquals(1, rocketDocLoaded.getSimulations().size());
		assertEquals(1, rocketDocLoaded.getSimulations().get(0).getSimulationExtensions().size());
		ScriptingExtension ext = (ScriptingExtension) rocketDocLoaded.getSimulations().get(0).getSimulationExtensions().get(0);
		assertEquals(false, ext.isEnabled());
		assertEquals(SIMULATION_EXTENSION_SCRIPT, ext.getScript());
	}
	
	
	@Test
	public void testTrustedScriptEnabledOnLoad() {
		OpenRocketDocument rocketDoc = TestRockets.makeTestRocket_v107_withSimulationExtension("TESTING");
		injector.getInstance(ScriptingUtil.class).setTrustedScript("JavaScript", "TESTING", true);
		StorageOptions options = new StorageOptions();
		File file = saveRocket(rocketDoc, options);
		OpenRocketDocument rocketDocLoaded = loadRocket(file.getPath());
		assertEquals(1, rocketDocLoaded.getSimulations().size());
		assertEquals(1, rocketDocLoaded.getSimulations().get(0).getSimulationExtensions().size());
		ScriptingExtension ext = (ScriptingExtension) rocketDocLoaded.getSimulations().get(0).getSimulationExtensions().get(0);
		assertEquals(true, ext.isEnabled());
		assertEquals("TESTING", ext.getScript());
	}
	
	
	/*
	 * Test how accurate estimatedFileSize is.
	 * 
	 * Actual file is 5822 Bytes
	 * Estimated file is 440 Bytes (yeah....)
	 */
	@Test
	public void testEstimateFileSize() {
		OpenRocketDocument rocketDoc = TestRockets.makeTestRocket_v104_withSimulationData();
		
		StorageOptions options = new StorageOptions();
		options.setSaveSimulationData(true);
		
		long estimatedSize = saver.estimateFileSize(rocketDoc, options);
		
		// TODO: fix estimateFileSize so that it's a lot more accurate
	}

	/**
	 * Test sim status with/without sim data in file.
	 */
	@Test
	public void TestSimStatus() {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		OpenRocketDocument rocketDoc = OpenRocketDocumentFactory.createDocumentFromRocket(rocket);
		
		// Hook up some simulations.
		// First sim will not have options set
		Simulation sim1 = new Simulation(rocket);
		rocketDoc.addSimulation(sim1);

		// Second sim has options, but hasn't been simulated
		Simulation sim2 = new Simulation(rocket);
        sim2.getOptions().setISAAtmosphere(true);
        sim2.getOptions().setTimeStep(0.05);
        sim2.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		rocketDoc.addSimulation(sim2);

		// Third sim has been executed
		Simulation sim3 = new Simulation(rocket);
        sim3.getOptions().setISAAtmosphere(true);
        sim3.getOptions().setTimeStep(0.05);
        sim3.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		try {
			sim3.simulate();
		} catch (Exception e) {
			fail(e.toString());
		}
		rocketDoc.addSimulation(sim3);

		// Fourth sim has been executed, then configuration changed
		Simulation sim4 = new Simulation(rocket);
        sim4.getOptions().setISAAtmosphere(true);
        sim4.getOptions().setTimeStep(0.05);
        sim4.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		try {
			sim4.simulate();
		} catch (Exception e) {
			fail(e.toString());
		}
        sim4.getOptions().setTimeStep(0.1);
		rocketDoc.addSimulation(sim4);

		// save, then load document
		StorageOptions options = new StorageOptions();
		options.setSaveSimulationData(true);

		File file = saveRocket(rocketDoc, options);
		OpenRocketDocument rocketDocLoaded = loadRocket(file.getPath());
		
		assertEquals(Simulation.Status.CANT_RUN, rocketDocLoaded.getSimulations().get(0).getStatus());
		assertEquals(Simulation.Status.NOT_SIMULATED, rocketDocLoaded.getSimulations().get(1).getStatus());
		assertEquals(Simulation.Status.LOADED, rocketDocLoaded.getSimulations().get(2).getStatus());		
		assertEquals(Simulation.Status.OUTDATED, rocketDocLoaded.getSimulations().get(3).getStatus());
	}
	
	////////////////////////////////
	// Tests for File Version 1.7 // 
	////////////////////////////////
	
	@Test
	public void testFileVersion108_withSimulationExtension() {
		OpenRocketDocument rocketDoc = TestRockets.makeTestRocket_v107_withSimulationExtension(SIMULATION_EXTENSION_SCRIPT);
		assertEquals(108, getCalculatedFileVersion(rocketDoc));
	}
	

	////////////////////////////////
	/*
	 * Utility Functions
	 */
	
	private int getCalculatedFileVersion(OpenRocketDocument rocketDoc) {
		int fileVersion = this.saver.testAccessor_calculateNecessaryFileVersion(rocketDoc, null);
		return fileVersion;
	}
	
	private OpenRocketDocument loadRocket(String fileName) {
		GeneralRocketLoader loader = new GeneralRocketLoader(new File(fileName));
		OpenRocketDocument rocketDoc = null;
		try {
			rocketDoc = loader.load();
		} catch (RocketLoadException e) {
			e.printStackTrace();
			fail("RocketLoadException while loading file " + fileName + " : " + e.getMessage());
		}
		return rocketDoc;
	}
	
	private File saveRocket(OpenRocketDocument rocketDoc, StorageOptions options) {
		File file = null;
		OutputStream out = null;
		try {
			file = File.createTempFile( TMP_DIR.getName(), ".ork");
			out = new FileOutputStream(file);
			this.saver.save(out, rocketDoc, options, new WarningSet(), new ErrorSet());
		} catch (FileNotFoundException e) {
			fail("FileNotFound saving temp file in: " + TMP_DIR.getName() + ": " + e.getMessage());
		} catch (IOException e) {
			fail("IOException saving temp file in: " + TMP_DIR.getName() + ": " + e.getMessage());
		}finally {
			try {
				if (out != null) {
					out.close();
				}
			} catch (IOException e) {
				fail("Unable to close output stream for temp file in " + TMP_DIR.getName() + ": " + e.getMessage());
			}
		}
		
		return file;
	}
	
	
	private static ThrustCurveMotor readMotor() {
		GeneralMotorLoader loader = new GeneralMotorLoader();
		InputStream is = OpenRocketSaverTest.class.getResourceAsStream("/net/sf/openrocket/Estes_A8.rse");
		assertNotNull("Problem in unit test, cannot find Estes_A8.rse", is);
		try {
			for (ThrustCurveMotor.Builder m : loader.load(is, "Estes_A8.rse")) {
				return m.build();
			}
			is.close();
		} catch (IOException e) {
			e.printStackTrace();
			fail("IOException: " + e);
		}
		throw new RuntimeException("Could not load motor");
	}
	
	private static class EmptyComponentDbProvider implements Provider<ComponentPresetDao> {
		
		final ComponentPresetDao db = new ComponentPresetDatabase();
		
		@Override
		public ComponentPresetDao get() {
			return db;
		}
	}
	
	private static class MotorDbProvider implements Provider<ThrustCurveMotorSetDatabase> {
		
		final ThrustCurveMotorSetDatabase db = new ThrustCurveMotorSetDatabase();
		
		public MotorDbProvider() {
			db.addMotor(readMotor());
			db.addMotor( new ThrustCurveMotor.Builder()
					.setManufacturer(Manufacturer.getManufacturer("A"))
					.setDesignation("F12X")
					.setDescription("Desc")
					.setMotorType(Motor.Type.UNKNOWN)
					.setStandardDelays(new double[] {})
					.setDiameter(0.024)
					.setLength(0.07)
					.setTimePoints(new double[] { 0, 1, 2 })
					.setThrustPoints(new double[] { 0, 1, 0 })
					.setCGPoints(new Coordinate[] { Coordinate.NUL, Coordinate.NUL, Coordinate.NUL })
					.setDigest("digestA")
					.build());

			assertEquals(2, db.getMotorSets().size());
		}
		
		@Override
		public ThrustCurveMotorSetDatabase get() {
			return db;
		}
	}
	
	
}
