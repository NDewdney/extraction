package mesme;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.Set;
import java.util.StringTokenizer;

import libsvm.*;
import org.tartarus.snowball.ext.*;

public class MweActiveLearner {

  public MweActiveLearner () {
  	loadDefaultModels();
  }
  
	private class FeaturePoint {
    // Possibly could re-factor to use svm_node rather than intermediate class
		public int featureID = 0; // featureID 0 will be interpreted as class label
		public double value = 0;
		
	  public FeaturePoint (int point, double val) {
		  featureID = point; // featureID 0 will be interpreted as class label
		  value = val;
	  }
	}
	
	private svm_model currentModel = null;
	private HashMap<String,svm_model> modelMap = new HashMap<String,svm_model>();
	
	private int updateRate = 500; // default of 500 new samples to initiate re-training
	private int newVectorNo = 0; // number of added vectors for next active learning cycle
	private double confidenceLevel = 0.667; // confidence threshold at which to add training vectors
	
	private HashMap<String,LinkedList<FeaturePoint[]>> modelVectorMap = new HashMap<String,LinkedList<FeaturePoint[]>>();
	private LinkedList<FeaturePoint[]> trainVectors;
	
  public void loadModel (String modelName, String modelFilename) {
    try {
      svm_model model = svm.svm_load_model(modelFilename);
      modelMap.put(modelName, model);
      currentModel = model;
    } catch (IOException e)  {
    	System.err.println ("Can not load "+modelFilename+" Model not created.");
    }  	
  }
  
  public String[] getLoadedModelNames () {
  	String[] names = (String[]) modelMap.keySet().toArray();
  	return names;
  }
  
  public void setDefaultModel (String modelName) {
  	if (modelMap.containsKey(modelName)) {
  		currentModel = modelMap.get(modelName);
  		trainVectors = modelVectorMap.get(modelName);
  		if (trainVectors==null) {
  			trainVectors = new LinkedList<FeaturePoint[]>();
  			modelVectorMap.put(modelName, trainVectors);
  		}
  	}
  }
  
  public void addTrainingData (LinkedList<FeaturePoint> fps) {
		FeaturePoint[] vec = (FeaturePoint[]) fps.toArray();
		trainVectors.add(vec);
	}
	
	public void addTrainingData (String vector) {
		LinkedList<FeaturePoint> fps = decodedVectorString(vector);
		addTrainingData (fps);
	}
	
	public void setUpdateRate (int sampleNo) {
		if (sampleNo < 0) sampleNo = 0;
		updateRate = sampleNo;
	}
	
	public int getUpdateRate () {
		return updateRate;
	}

	public int getPendingNo () {
		return newVectorNo;
	}

	public void setConfidenceLevel (double conf) {
		if (conf >= 0.5 && conf <= 1.0) confidenceLevel = conf;
	}
	
	public double getConfidenceLevel () {
		return confidenceLevel;
	}

	public void svmTrain(String modelFilename, boolean addDate) {
    svm_problem prob = new svm_problem();
    int dataCount = trainVectors.size();
    prob.y = new double[dataCount];  // label
    prob.l = dataCount;
    prob.x = new svm_node[dataCount][]; // feature vector    

    Iterator<FeaturePoint[]> tvIt = trainVectors.iterator();
    int i = 0;
    while (tvIt.hasNext()) {
    	FeaturePoint[] vector = tvIt.next();
    	prob.y[i] = vector[0].value;
    	for (int j=1;j<=vector.length;++j) {
        svm_node node = new svm_node();
        node.index = vector[j].featureID;
        node.value = vector[j].value;
        prob.x[i][j-1] = node;
    	}
    	++i;
    }
    
    svm_parameter param = new svm_parameter();
    param.probability = 1;
    param.gamma = 0.5;
    param.nu = 0.5;
    param.C = 1;
    param.svm_type = svm_parameter.C_SVC;
    param.kernel_type = svm_parameter.LINEAR;       
    param.cache_size = 20000;
    param.eps = 0.001;      

    currentModel = svm.svm_train(prob, param);

    Date timestamp = new Date();
    SimpleDateFormat dateString = new SimpleDateFormat("yyMMddhhmmss");
    if (addDate) {
    	modelFilename = new String (modelFilename+"-"+dateString.format(timestamp));
    }
    try {
      svm.svm_save_model(modelFilename, currentModel);
    } catch (IOException e)  {
    	System.err.println ("Can not save "+modelFilename+" Model not updated.");
    }
	}
	
// Currently only two class - will need to adapt for multiple MWE classes
	public double[] evaluate(String vector, String modelName) {
		LinkedList<FeaturePoint> fps = decodedVectorString (vector);
		return evaluate (fps, modelName);
	}
	
	public double[] evaluate(LinkedList<FeaturePoint> fps, String modelName) { // Now need to create multiple model support, train and test!
		int totalClasses = 2;
		double[] probEstimates = {0, 1.0};
		currentModel = modelMap.get(modelName);
    if (currentModel==null) return probEstimates;
    int vectorLength = fps.size();
		svm_node[] nodes = new svm_node[vectorLength];

		Iterator<FeaturePoint> fpsIt = fps.iterator();
		int i = 0;
		while (fpsIt.hasNext()) {
			FeaturePoint fp = fpsIt.next();
			svm_node node = new svm_node();
			node.index = fp.featureID;
			node.value = fp.value;
			nodes[i++] = node;
		}

		int[] labels = new int[totalClasses];
		svm.svm_get_labels(currentModel, labels); // not sure we need these really
		double v = svm.svm_predict_probability(currentModel, nodes, probEstimates);

		return probEstimates;
	}
	
/*
 * decodeVectorString assumes a string in the form of "1 1:0.123 2:0.789"... i.e. standard SVM vector string format
 * with the first number being a class label if present. No checking of validity of the string is carried out!
 * It returns a linked list of FeaturePoints representing the sparse vector
 */
	private LinkedList<FeaturePoint> decodedVectorString(String vector) {
		LinkedList<FeaturePoint> fps = new LinkedList<FeaturePoint>();
		StringTokenizer pointList = new StringTokenizer(vector,": ");
		if (vector.indexOf(' ') < vector.indexOf(':')) {
			// There is a class label at the start of the string
			String cLabel = pointList.nextToken();
			int c = Integer.decode(cLabel);
			FeaturePoint fp = new FeaturePoint(0, (double) c);
			fps.add(fp);
		}
		
		while (pointList.hasMoreTokens()) {
			String token = pointList.nextToken();
			int fn = Integer.decode(token);
			double fv = 0;
			if (pointList.hasMoreTokens()) {
				token = pointList.nextToken();
				fv = Double.valueOf(token);
			}
			FeaturePoint fp = new FeaturePoint(fn, fv);
			fps.add(fp);
		}		
		return fps;
	}	

	/*
	 * load default models for MWEs based on training from wiki50 corpus. These models can be over-ridden
	 * but provide a starting point. The models are stored in the resource folder under src.
	 */
	private void loadDefaultModels() {

		//Get file from resources folder
		ClassLoader classLoader = getClass().getClassLoader();
		File file = new File(classLoader.getResource("./resources/modelConfig.txt").getFile());

		try (Scanner scanner = new Scanner(file)) {

			while (scanner.hasNextLine()) {
				String line = scanner.nextLine();
				// line should hold name of svm model and the model filename
				String modelName = line.substring(0, line.indexOf(" "));
				String modelFilename = line.substring(line.indexOf(" ")+1);
				if (!modelFilename.startsWith("./resources/")) modelFilename = new String("./resources/"+modelFilename);
				URL resource = classLoader.getResource(modelFilename);
			  if (resource != null) {
					modelFilename = resource.getFile();
  				loadModel(modelName, modelFilename);
	  		}
				else {
					System.err.println("Missing resource: "+modelFilename);
				}
			}
			scanner.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void main (String args[]) {
		if (args.length > 2) {
			System.err.println("No specified files");
			System.err.println("Usage: MweActiveLearner trainingFilename modelFilename [testFilename] [learning rate]");
			System.exit(-1);
		}
		MweActiveLearner learner = new MweActiveLearner();
		learner.loadDefaultModels();
	}
}
