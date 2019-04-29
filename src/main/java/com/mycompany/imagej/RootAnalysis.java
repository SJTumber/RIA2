package com.mycompany.imagej;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.File;
import java.io.FilenameFilter;
import java.io.PrintWriter;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.ImageCalculator;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.EDM;
import ij.process.BinaryProcessor;
import ij.process.ByteProcessor;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.FloatPolygon;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;

import com.mycompany.imagej.Directionality;
import com.mycompany.imagej.EllipticFD;
import com.mycompany.imagej.Diameter;
import com.mycompany.imagej.Tissue;
import com.mycompany.imagej.Rotate;
import com.mycompany.imagej.Geometry;


public class RootAnalysis {
	
	// Export parameters
	static File dirAll, dirParam;//, dirConvex;
	static File[] images; 		
	static String  csvParamFolder, csvParamAnalysis, imName, baseName, fullName, tpsFolder, efdFolder, shapeFolder;
	
	// Analysis parameters
	static int nROI, nEFD, nCoord = 10, counter, nSlices = 30;
	static float scalePix, scaleCm, scale, rootMinSize;
	static final float epsilon = 1e-9f;
	static boolean blackRoots, saveImages, saveTips, verbatim, saveTPS, saveEFD, saveShapes;

	// Estimators
	static float area, depth, length, width, comX, comY, Ymid, Xmid, bX, bY;
	static float[] params = new float[23+(nSlices*2)+(nCoord*4)];
    static float[] xCoord;
    static float[] yCoord;
    static float[] diffCoord;
    static float[] cumulCoord;
    
    // Tools
	static ResultsTable rt = new ResultsTable();
	static PrintWriter pwParam, pwTPS, pwEFD, pwAnalysis;

    // Frankie
    static boolean saveDepth;
    static PrintWriter pwDA, pwDL, pwDD;
    static String depthAFolder, depthLFolder, depthDFolder;
    
	static ParticleAnalyzer pa;	
	static Analyzer an;
	
	/**
	 * Constructor
	 * @param f = File containing the different images
	 * @param file = where to save csv file
	 * @param scaleP = scale, in pixels
	 * @param scaleC = scale, in cm
	 */
	public RootAnalysis(File f,
			String file,
			float scaleP,
			float scaleC,
			boolean black,
			float minsize,
			boolean verb,
			boolean save,
			boolean saveT,
			boolean tps,
			boolean efd,
			boolean shapes,
			String dirS
			){
		
		// Set up the different variables
		scalePix = scaleP;
		scaleCm = scaleC;
		dirAll = f;
		csvParamFolder = file;
		csvParamAnalysis = file.substring(0, file.length()-4)+"-analysis.csv";
		tpsFolder = file.substring(0, file.length()-4)+"-shape.tps";
		efdFolder = file.substring(0, file.length()-4)+"-efd.csv";
		saveTPS = tps;
		saveEFD = efd;
		saveShapes = shapes;
		shapeFolder = dirS;

		// Frankie
        saveDepth = true;
        depthAFolder = file.substring(0, file.length()-4)+"-depthA.txt";
        depthLFolder = file.substring(0, file.length()-4)+"-depthL.txt";
        depthDFolder = file.substring(0, file.length()-4)+"-depthD.txt";
        
		blackRoots = black;
		rootMinSize = minsize;
		saveImages = save;
		saveTips = saveT;
		verbatim = verb;
		
	    xCoord = new float[nCoord * 2];
	    yCoord = new float[nCoord * 2];
	    diffCoord = new float[nCoord];
	    cumulCoord = new float[nCoord];
	    
	    nEFD = 10;
	    
		// Analyze the plants
		analyze();
	}


	/**
	 * Perform the analysis of all the images
	 */
	public void analyze(){

		ImagePlus nextImage;
		
        // Get all the images files in the directory

		images = dirAll.listFiles();
		// for (File el : images) if (el.isHidden()) el.delete();

        images = dirAll.listFiles((file) -> {
            String name = file.getName();
            return name.toLowerCase().endsWith(".jpg")
                    || name.toLowerCase().endsWith(".tiff")
                    || name.toLowerCase().endsWith(".tif")
                    || name.toLowerCase().endsWith(".jpeg")
                    || name.toLowerCase().endsWith(".png");
        });


		// Counter for the processing time
		IJ.log("Root image analysis started: "+dirAll.getAbsolutePath());
		long startD = System.currentTimeMillis(); 
		int counter = 0;	

		// Initialize CSV
		pwParam = Util.initializeCSV(csvParamFolder);
		pwAnalysis = Util.initializeCSV(csvParamAnalysis);
		printParamCSVHeader();		
		printAnalysisCSVHeader();		
		
		if(saveTPS) pwTPS = Util.initializeCSV(tpsFolder);
		if(saveEFD) pwEFD = Util.initializeCSV(efdFolder);
		if(saveEFD) printEFDCSVHeader();

        // Frankie
        if(saveDepth) {
            pwDA = Util.initializeCSV(depthAFolder);
            pwDL = Util.initializeCSV(depthLFolder);
            pwDD = Util.initializeCSV(depthDFolder);
        }
        
		// Create the folder structure to store the images			
		if(saveImages || saveShapes){
			File dirSave; 
			dirSave = Util.createFolderStructure(dirAll.getAbsolutePath(), false, false, false, true);
			dirParam = new File(dirSave.getAbsolutePath()+"/param/");
		}
		
		// Navigate the different images in the time serie
		int percent = 0;
	    double progression;
		for(int i = 0; i < images.length; i++){
			
			long startD1 = System.currentTimeMillis();

			// Open the image
			nextImage = IJ.openImage(images[i].getAbsolutePath());
			
			progression = ((double) i/images.length)*100;
  		  	if(progression > percent){    			
  			  IJ.log(percent+" % of the rsml files converted. "+(images.length-i)+" files remaining.");
  			  System.out.println(percent+" % of the rsml files converted. "+(images.length-i)+" files remaining.");
  			  percent = percent + 5;
  		  	}
	    	
			// Reset the ROI to the size of the image. This is done to prevent previously drawn ROI (ImageJ keep them in memory) to empede the analysis
			nextImage.setRoi(0, 0, nextImage.getWidth(), nextImage.getHeight());

			// Process the name to retrieve the experiment information
			baseName = images[i].getName();
			fullName = images[i].getAbsolutePath();
			
			// Measure the image	
			try{
				getDescriptors(nextImage, i);
				sendAnalysisToCSV(nextImage.getTitle(), nextImage.getWidth(), nextImage.getHeight(), startD1, System.currentTimeMillis());
			}catch(Exception e){
			    System.out.println("I am at the exception handling routine");
			    e.printStackTrace(System.out);
				System.out.println(e.toString());
			}
			// Close the current image
			nextImage.flush(); nextImage.close(); 
			counter ++;
			
			IJ.log("Loading the image "+(i+1)+"/"+images.length+" in "+(startD1 - System.currentTimeMillis()));

		}
		
		// Compute the time taken for the analysis
		long endD = System.currentTimeMillis();
		IJ.log("------------------------");
		IJ.log(counter+" images analyzed in "+(endD - startD)+" ms");		
	}

	
	private void getDescriptors(ImagePlus current, int count){
				
		// Initalisation of the image
		
		ImagePlus currentImage = current.duplicate();		
		
		// Pre-process the image
		if(verbatim) IJ.log("Pre-processing the image");
		ImageProcessor ip = currentImage.getProcessor();
		
		
		// Resize the image to speed up the analysis (resize to width = 800)		
		scale = scalePix / scaleCm;
		float widthCm = ip.getWidth() / scale;
//		int maxScale = 1000;
//		if(ip.getWidth() > maxScale){
//			float h = ip.getHeight();
//			float w = ip.getWidth();
//			ip = ip.resize(maxScale, (int) ((h / w) * maxScale));
//			scale = maxScale / widthCm;
//		}
		
		// Convert to 8bit image if needed
		if(ip.getBitDepth() != 8) ip = ip.convertToByte(true);

		// If the root is white on black, then invert the image
		if(!blackRoots){
			ip.invert();
			System.out.println("root inverted");
		}
		
		// Threshold the image (used to be Otsu
	    ip.setAutoThreshold("Default");
	    currentImage.setProcessor(ip);
	    
	    // Remove small particles in the image
		pa = new ParticleAnalyzer(ParticleAnalyzer.SHOW_MASKS, Measurements.AREA, rt, rootMinSize, 10e9, 0, 1);
		pa.analyze(currentImage);
		
		// Get the mask from the ParticuleAnalyser
		currentImage = IJ.getImage(); 
		currentImage.hide(); // Hide the mask, we do not want to display it.
		ip = currentImage.getProcessor();	    
	    
	    
	    // Reset calibration 
	    // This is needed in case the image was previously calibrated using ImageJ.
	    // TIFF images store the calibration...
		Calibration calDefault = new Calibration();
	    calDefault.setUnit("px");
		calDefault.pixelHeight = 1;
		calDefault.pixelWidth = 1;
	    currentImage.setCalibration(calDefault);
	    
		//----------------------------------------------------------------------------------------	
	    
	    //Get base coordinate of root system      	
      	IJ.run(currentImage, "Create Selection", "");
        Analyzer.setResultsTable(rt);
        rt.reset();
        an = new Analyzer(currentImage, Measurements.CENTER_OF_MASS | Measurements.RECT, rt);
        an.measure();
        
        Ymid = (float) rt.getValue("BY", 0);
        Xmid = (float) rt.getValue("XM", 0);
                    
        counter = 0;
        
		// Create skeleton
        ImagePlus skelImage = new ImagePlus();
		BinaryProcessor bp = new BinaryProcessor(new ByteProcessor(ip, true));
		//for(int i = 0; i < 5; i++) bp.smooth(); // Smooth the image for a better skeletonisation
		bp.autoThreshold();
		bp.skeletonize();
		//bp.invert();
		skelImage.setProcessor(bp);
		
	    if(saveImages) IJ.save(skelImage, dirParam.getAbsolutePath()+"/"+baseName+"_skeleton.tiff");
	 
		
		skelImage.setRoi(0, 0, ip.getWidth(), ip.getHeight());
		currentImage.setRoi(0, 0, ip.getWidth(), ip.getHeight());
		
		// skel.show(); im.show();
		
        // Get area of the root system

        Diameter dia = new Diameter(currentImage, skelImage);

        Tissue ts = new Tissue(currentImage, skelImage);

        Rotate.getVolume(currentImage);
            
        Geometry gy = new Geometry(currentImage, skelImage);
        
        // getDensityEllipses(im.duplicate());

        // getDensityRectangles(im.duplicate());

        // getDirectionality(im.duplicate());
        
        // -- getWhiteEllipses(im.duplicate());
		
        // getPixelsCount(skel.duplicate(), im.duplicate());
        
        PixelProfile pp = new PixelProfile(skelImage, gy.geo);

        ConvexHull ch = new ConvexHull(currentImage.duplicate(), saveEFD);
     
        Coordinates co = new Coordinates(currentImage.duplicate(), gy.geo);

        // Frankie:
        DepthProfile dp = new DepthProfile(currentImage.duplicate(), skelImage.duplicate());
        
        counter = 0;
        
	    // Save the images for post-processing check
		currentImage.flush(); currentImage.close();
		skelImage.flush(); skelImage.close();
		
	    // sendParametersToCSV();
	}
		

	/**
	 * 
	 * @param im input image
	 * @param skel skeleton of image
	 */
	private void getDiameters(ImagePlus im, ImagePlus skel){
		
		if(verbatim) IJ.log("Get diameter values");
        
		EDM edm = new EDM();
		ImageCalculator ic = new ImageCalculator();
		
		// Create EDM mask
		ImageProcessor ip = im.getProcessor();
		ip.autoThreshold();
		//ip.invert();

        // Frankie: added this to show result of EDM
        // ImageProcessor imdp = ip.duplicate();
        // edm.toEDM(imdp);
        // ImagePlus ipd = new ImagePlus("edm.toEDM", imdp);
        // ipd.show();

		edm.run(ip);
		im.setProcessor(ip);

        // Frankie: added this to show result of EDM
        // ImageProcessor imdp2 = ip.duplicate();
        // ImagePlus ipd2 = new ImagePlus("edm.run", imdp2);
        // ipd2.show();

		// Create EDM Skeleton
		im = ic.run("AND create", im, skel);

        ImagePlus ipd3 = new ImagePlus("AND create", im.getProcessor());
        // Frankie: added
        // ipd3.show();

        // Frankie
        // Calculate tissue volume
        // Calculate histogram

		IJ.setThreshold(im, 1, 255);
		Analyzer.setResultsTable(rt);
		rt.reset();
		IJ.run(im, "Create Selection", "");
		an = new Analyzer(im, Measurements.MODE | Measurements.MEAN | Measurements.MIN_MAX , rt);
		an.measure();
		
		params[counter++] = (float) rt.getValue("Max", 0) / scale;
		params[counter++] = (float) rt.getValue("Mean", 0) / scale;
		params[counter++] = (float) rt.getValue("Mode", 0) / scale;
		
		im.close();
		skel.close();
	}


	/**
	 * Print Parameters CSV header
	 */
	private void printAnalysisCSVHeader(){	
		String toPrint = "image";
		toPrint = toPrint.concat(",width,height,time_start, time_end");
		pwAnalysis.println(toPrint);
		pwAnalysis.flush();
	}	
	
	/**
	 * Print Parameters CSV header
	 */
	private void sendAnalysisToCSV(String image, int width, int height, long time1, long time2){
		String toPrint = image+","+width+","+height+","+time1+","+time2;
		pwAnalysis.println(toPrint);
		pwAnalysis.flush();
	}		
	
	/**
	 * Print Parameters CSV header
	 */
	private void printParamCSVHeader(){	
		String toPrint = "image";
		toPrint = toPrint.concat(",diam_max,diam_mean,diam_mode");
		toPrint = toPrint.concat(",length,area,width,depth,width_depth_ratio,com_x,com_y");
		toPrint = toPrint.concat(",ellips_025,ellips_050,ellips_075,ellips_100");
		toPrint = toPrint.concat(",rect_020,rect_040,rect_060,rect_080");
		toPrint = toPrint.concat(",directionality");
		toPrint = toPrint.concat(",tip_count");
		for(int i = 0; i < nSlices; i++) toPrint = toPrint.concat(",cross_hori_"+i+"_mean,cross_hori_"+i+"_max");
		toPrint = toPrint.concat(",cross_vert_mean,cross_vert_max,convexhull");
		for(int i = 0; i < nCoord * 2; i++) toPrint = toPrint.concat(",coord_x"+i);
		for(int i = 0; i < nCoord; i++) toPrint = toPrint.concat(",diff_x"+i);
		for(int i = 0; i < nCoord; i++) toPrint = toPrint.concat(",cumul_x"+i);

		pwParam.println(toPrint);
		pwParam.flush();
	}


	
	/**
	 * send Shape Data To CSV
	 */
	private void sendShapeDataToTPS(float[] coordX, float[] coordY){	
		pwTPS.println("ID="+baseName);
		pwTPS.println("LM="+(nCoord*2));
		for(int i = 0; i < coordX.length; i++) pwTPS.println(coordX[i]+" "+coordY[i]);
		pwTPS.flush();
	}
	
	/**
	 * Print EFD CSV header
	 */
	private void printEFDCSVHeader(){	
		pwEFD.println("image, index, ax, ay, bx, by, efd");			
		pwEFD.flush();
	}
	/**
	 * Send EFD data to an CSV file
	 */
	private void sendEFDDataToCSV(int i, double ax, double ay, double bx, double by, double efd){	
		pwEFD.println(baseName +","+ i +","+ ax +","+ ay +","+ bx+","+ by+","+ efd);
		pwEFD.flush();
	}
	
	
	
   /**
    * Image filter	
    * @author guillaumelobet
    */
	public class ImageFilter extends javax.swing.filechooser.FileFilter{
		public boolean accept (File f) {
			if (f.isDirectory()) {
				return true;
			}

			String extension = getExtension(f);
			if (extension != null) {
				return (extension.equals("jpg")
                        || extension.equals("png")
                        || extension.equals("tif")
                        || extension.equals("tiff")
                        || extension.equals("jpeg"));

			}
			return false;
		}
	     
		public String getDescription () {
			return "Image files (*.jpg, *png, *tiff)";
		}
	      
		public String getExtension(File f) {
			String ext = null;
			String s = f.getName();
			int i = s.lastIndexOf('.');
			if (i > 0 &&  i < s.length() - 1) {
				ext = s.substring(i+1).toLowerCase();
			}
			return ext;
		}
	}	
	
}
