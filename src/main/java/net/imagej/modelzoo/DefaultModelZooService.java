/*-
 * #%L
 * This is the bioimage.io modelzoo library for ImageJ.
 * %%
 * Copyright (C) 2019 - 2020 Center for Systems Biology Dresden
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package net.imagej.modelzoo;

import io.scif.MissingLibraryException;
import net.imagej.modelzoo.consumer.DefaultModelZooPrediction;
import net.imagej.modelzoo.consumer.DefaultSingleImagePrediction;
import net.imagej.modelzoo.consumer.ModelZooPredictionOptions;
import net.imagej.modelzoo.consumer.SingleImagePrediction;
import net.imagej.modelzoo.consumer.commands.DefaultModelZooPredictionCommand;
import net.imagej.modelzoo.consumer.commands.SingleImagePredictionCommand;
import net.imagej.modelzoo.io.ModelZooIOPlugin;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.scijava.Context;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.io.location.Location;
import org.scijava.module.Module;
import org.scijava.module.ModuleException;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.PluginInfo;
import org.scijava.plugin.PluginService;
import org.scijava.plugin.SciJavaPlugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.UIService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

@Plugin(type = Service.class)
public class DefaultModelZooService extends AbstractService implements ModelZooService {

	@Parameter
	private Context context;

	@Parameter
	private PluginService pluginService;

	@Parameter
	private CommandService commandService;

	@Parameter
	private UIService uiService;

	@Override
	public ModelZooArchive open(String location) throws IOException {
		return createIOPlugin().open(location);
	}

	@Override
	public ModelZooArchive open(File location) throws IOException {
		return open(location.getAbsolutePath());
	}

	@Override
	public ModelZooArchive open(Location location) throws IOException {
		return open(new File(location.getURI()));
	}

	@Override
	public void save(ModelZooArchive trainedModel, String location) throws IOException {
		createIOPlugin().save(trainedModel, location);
	}

	@Override
	public void save(ModelZooArchive trainedModel, File location) {
		save(trainedModel, location.getAbsoluteFile());
	}

	@Override
	public void save(ModelZooArchive trainedModel, Location location) {
		save(trainedModel, new File(location.getURI()));
	}

	@Override
	public <TI extends RealType<TI>, TO extends RealType<TO>> RandomAccessibleInterval<TO> predict(ModelZooArchive <TI, TO> trainedModel, RandomAccessibleInterval<TI> input, String axes) throws FileNotFoundException, MissingLibraryException {
		return predict(trainedModel, input, axes, ModelZooPredictionOptions.options());
	}

	@Override
	public <TI extends RealType<TI>, TO extends RealType<TO>> RandomAccessibleInterval<TO> predict(ModelZooArchive<TI, TO> trainedModel, RandomAccessibleInterval<TI> input, String axes, ModelZooPredictionOptions options) throws FileNotFoundException, MissingLibraryException {
		String archivePrediction = trainedModel.getSpecification().getSource();
		SingleImagePrediction prediction = null;
		if(archivePrediction == null) {
			prediction = new DefaultSingleImagePrediction(getContext());
		} else {
			List<PluginInfo<SingleImagePrediction>> predictionCommands = pluginService.getPluginsOfType(SingleImagePrediction.class);
			for (PluginInfo<SingleImagePrediction> command : predictionCommands) {
				if(command.getAnnotation().name().equals(archivePrediction)) {
					prediction = pluginService.createInstance(command);
				}
			}
			if(prediction == null) {
				log().error("Could not find prediction plugin for model source " + archivePrediction + ". Exiting.");
				return null;
			}
		}
		prediction.setTrainedModel(trainedModel);
		prediction.setInput(input, axes);
		prediction.setBatchSize(options.values.batchSize());
		prediction.setCacheDir(options.values.cacheDirectory());
		prediction.setNumberOfTiles(options.values.numberOfTiles());
		prediction.setTilingEnabled(options.values.tilingEnabled());
		prediction.run();
		return prediction.getOutput();
	}

	@Override
	public <TI extends RealType<TI>, TO extends RealType<TO>> void predictInteractive(ModelZooArchive <TI, TO> trainedModel) throws FileNotFoundException, ModuleException {
		List<PluginInfo<SingleImagePredictionCommand>> predictionCommands = pluginService.getPluginsOfType(SingleImagePredictionCommand.class);
		String archivePrediction = trainedModel.getSpecification().getSource();
		Module mycommand = null;
		if(archivePrediction != null) {
			for (PluginInfo<SingleImagePredictionCommand> command : predictionCommands) {
				if(command.getAnnotation().name().equals(archivePrediction)) {
					CommandInfo commandInfo = commandService.getCommand(command.getClassName());
					mycommand = commandInfo.createModule();
				}
			}
		}
		if(mycommand == null) {
//			mycommand = commandService.getCommand(DefaultModelZooPredictionCommand.class).createModule();
			uiService.showDialog("Could not find suitable prediction handler for source " + archivePrediction + ".", DialogPrompt.MessageType.ERROR_MESSAGE);
			return;
		}
		String modelFileParameter = "modelFile";
		File value = new File(trainedModel.getSource().getURI());
		mycommand.setInput(modelFileParameter, value);
		mycommand.resolveInput(modelFileParameter);
		commandService.moduleService().run(mycommand, true);
	}

	private ModelZooIOPlugin createIOPlugin() {
		ModelZooIOPlugin modelZooIOPlugin = new ModelZooIOPlugin();
		getContext().inject(modelZooIOPlugin);
		return modelZooIOPlugin;
	}
}
