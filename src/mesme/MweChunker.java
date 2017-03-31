package mesme;

import java.util.*;

import mesme.MweActiveLearner;
import mesme.MweEngineDefinition;
import mesme.MweStateEngine;
import mesme.MweSvmVectorMaker;

import java.io.*;

public class MweChunker {
	// Required state engines
  private MweStateEngine nounPhraseEngine = new MweStateEngine();
  private MweStateEngine verbPhraseEngine = new MweStateEngine();
  
  private HashSet<String> posTagSet = new HashSet<String>();
  private String[] posTagList = {"$", "``", "''", "(", ")", ",", "--", ".", ":", "CC", "CD", "DT", "EX", "FW", "IN", "JJ", "JJR",
  		                           "JJS", "LS", "MD", "NN", "NNP", "NNPS", "NNS", "PDT", "POS", "PRP", "PRP$", "RB", "RBR", "RBS", 
  		                           "RP", "SYM", "TO", "UH", "VB", "VBD", "VBG", "VBN", "VBP", "VBZ", "WDT", "WP", "WP$", "WRB",
  		                           "USR", "URL", "RT", "HT", "#"};

  private HashMap<String,String> tag2tagMap = new HashMap<String,String>();
  private String[] tagMappings = {"RT", "UH", "HT", "NN", "#", "DT", "USR", "NNP", ":", "."};
    
  private HashSet<String> filterSet = new HashSet<String>();
  private String[] filterTags = {"VB", "VBD", "VBG", "VBN", "VBP", "VBZ", "RB", "RBR", "RBS", "RP", "TO"};
    
  private HashSet<String> orthoSet = new HashSet<String>();
  private String[] orthoNames = {"punc", "numb", "lower", "lowNum", "upper", "upNum", "mixed", "mixNum"};

  private int orthoClass (String word) {
  	int ortho = 0;
  	char[] wordChrs = word.toCharArray();
  	for (int i=0;i<wordChrs.length;++i) {
  		char c = wordChrs[i];
  		if (c>='0' && c<='9') ortho = ortho | 1;
  		if (c>='a' && c<='z') ortho = ortho | 2;
  		if (c>='A' && c<='A') ortho = ortho | 4;
  	}
  	return ortho;
  }

  private MweSvmVectorMaker vectorMaker;
  
  /* switches for pattern sequence filtering */
  private boolean allLVCmode = false;
  
  public void setAllLVCmode (boolean mode) {
  	allLVCmode = mode;
  }
  
  public boolean getAllLVCmode () {
  	return allLVCmode;
  }
  
  /* chunker output lists - should be re-factored to be combined lists for each mwe type! */
	private LinkedList<String> npChunks = new LinkedList<String>();
  private LinkedList<String> lvcChunks = new LinkedList<String>();
	private LinkedList<String> vpcChunks = new LinkedList<String>();
	private LinkedList<String> pronouns = new LinkedList<String>();
	private LinkedList<Integer> npPositions = new LinkedList<Integer>();
	private LinkedList<Integer> lvcPositions = new LinkedList<Integer>();
	private LinkedList<Integer> vpcPositions = new LinkedList<Integer>();
	private LinkedList<Integer> npEndpoints = new LinkedList<Integer>();
	private LinkedList<Integer> lvcEndpoints = new LinkedList<Integer>();
	private LinkedList<Integer> vpcEndpoints = new LinkedList<Integer>();
	private LinkedList<String> npVectors = new LinkedList<String>();
  private LinkedList<String> lvcVectors = new LinkedList<String>();
	private LinkedList<String> vpcVectors = new LinkedList<String>();
	private LinkedList<String[]> npTags = new LinkedList<String[]>();
  private LinkedList<String[]> lvcTags = new LinkedList<String[]>();
	private LinkedList<String[]> vpcTags = new LinkedList<String[]>();

	private MweActiveLearner activeLearner = new MweActiveLearner();
	private boolean learning = false;
	private double thresholdNPC = 0.5;
	private double thresholdVPC = 0.5;
	private double thresholdLVC = 0.5;
	
	
  private void initParams () {
    for (int i=0;i<posTagList.length;++i) posTagSet.add(posTagList[i]);	
    for (int i=0;i<filterTags.length;++i) filterSet.add(filterTags[i]);	
    for (int i=0;i<orthoNames.length;++i) orthoSet.add(orthoNames[i]);
    for (int i=0;i<tagMappings.length;i+=2) tag2tagMap.put(tagMappings[i], tagMappings[i+1]);
    vectorMaker = new MweSvmVectorMaker(posTagSet, orthoSet);
  }
  
  private void initEngines () {
  	nounPhraseEngine.initialise(MweEngineDefinition.englishNominalCompounds);
  	verbPhraseEngine.initialise(MweEngineDefinition.englishVerbalCompounds);
  }
  
  /*
 * MweChunker takes a sequence of tags and the corresponding tokens and parses the tags. Sequences of tags that 
 * correspond to the bigram rules below are used to chunk the associated tokens into multi-word-expressions, and stored
 */

	public MweChunker (String posString, String tokenString) {
		initParams();
		initEngines();
		chunk (posString, tokenString);
	}
	
	public MweChunker (String posString, String tokenString, boolean filterLVCpatterns) {
		initParams();
		allLVCmode = filterLVCpatterns;
		initEngines();
		chunk (posString, tokenString);
	}
	
	public MweChunker () {
		initParams();
		initEngines();
	}
	
	public MweChunker (boolean filterLVCpatterns) {
		initParams();
		allLVCmode = filterLVCpatterns;
		initEngines();
	}

/*
 * set LVCpatterns: default is false. Setting true will allow VB [DT][JJ*] NN* patterns to be passed as potential LVCs
 */
	
	public void setLVCpatterns (boolean filterLVCpatterns) {
		allLVCmode = filterLVCpatterns;
	}

	public void setLearningMode (boolean learningOn, int updateRate) {
		learning = learningOn;
		if (activeLearner==null) activeLearner = new MweActiveLearner (); // need to set parameters
	}

  public boolean inLearningMode() {
  	return learning;
  }
  
	/*
  * POS tagging generally distinguishes tense of verb but not any deeper linguistic role. The verb 'to be' often signals a reference to
  * a subsequent concept, (which itself may be a MWE). The following noun is not going to modify the sense of "to be" and therefore although
  * the POS pattern suggests an LVC it is not. 
  */
	private String[] defeats = {"is", "was", "are", "were", "be", "been", "become", "became", "becomes", "becoming", "being"};
  private boolean defeatLVC(String phrase) {
  	if (phrase.contains(" ")) {
  		String opening = phrase.substring(0,phrase.indexOf(' ')).toLowerCase();
  	  for (int i=0;i<defeats.length;++i) {
  		  if (opening.equals(defeats[i])) return true;
  	  }
  	}
  	return false;
  }

  /*
   * POS tagging generally distinguishes tense of verb but not any deeper linguistic role. 
   */
 	private String[] vpcDefeats = {"that", "this"};
  private boolean defeatVPC(String vpcPhrase) {
  	int ep = vpcPhrase.length();
  	if (vpcPhrase.endsWith(" ")) while (ep >0 && vpcPhrase.charAt(ep-1)==' ') ep--;
  	String phrase = vpcPhrase.substring(0,ep).toLowerCase();
  	if (phrase.contains(" ")) {
   	  String lastWord = phrase.substring(phrase.lastIndexOf(' ')+1);
   	  for (int i=0;i<vpcDefeats.length;++i) {
   		  if (lastWord.equals(vpcDefeats[i])) {
   		  	return true;
   		  }
   	  }
  	}
   	return false;
   }

  private void filterVPCs () {
  	Iterator<String> vpcIt = vpcChunks.iterator();
  	int lcount = 0;
    LinkedList<Integer> rems = new LinkedList<Integer>();
  	while (vpcIt.hasNext()) {
  		String vpc = vpcIt.next();
  		if (defeatVPC(vpc)||defeatLVC(vpc)) {
  			rems.add(lcount);
  		}
  		else lcount++;
  	}
  	Iterator<Integer> rIt = rems.iterator();
  	while (rIt.hasNext()) {
  		lcount = rIt.next();
			vpcChunks.remove(lcount);
			vpcPositions.remove(lcount);
  	}
  }
  
  private void addNPC (String phrase, int offset, String[] tags, String[] tokens, int startTokNo, int mweTokens) {
    // if just a single noun the don't bother to evaluate MWE
  	String[] tgs = new String[mweTokens];
  	if (mweTokens==1) {
      npChunks.add(new String(phrase));
    	npPositions.add(offset);
    	npVectors.add("none");
    	tgs[0] = tags[startTokNo];
    	npTags.add(tgs);
    	return;
    }
  	String[] tagWindow = new String[mweTokens+4];
  	String[] tokenWindow = new String[mweTokens];
  	String[] orthographics = new String[mweTokens+4];
  	int s = startTokNo - 2;
  	int e = startTokNo+mweTokens+2;
  	int i = 0;
  	while (s < e) {
  		if (s<0 || s>=(tokens.length-1)) {
  			tagWindow[i] = ".";
  			orthographics[i] = orthoNames[0];
  		} else {
  			tagWindow[i] = tags[s];
  			orthographics[i] = orthoNames[orthoClass(tokens[s])];
  		}
  		i++;
  		s++;
  	}
  	for (int j=0;j<mweTokens;++j) {
  		tokenWindow[j] = tokens[startTokNo+j];
  	}
  	String vector = vectorMaker.svmVector(tagWindow, tokenWindow, orthographics);
  	double[] classProbs = {1,0};
  	if (learning) {
  	  classProbs = activeLearner.evaluate(vector, "npcModel");  // confidence for each class. MWE should be class 1
  	}
  	// Accept NPC if classes as such, i.e. class 1 prob above threshold (default 0.5)
  	if (classProbs[0] <= thresholdNPC) return;
    npChunks.add(new String(phrase));
  	npPositions.add(offset);
  	npVectors.add(vector);
  	for (int z=0;z<mweTokens;++z) tgs[z] = tags[startTokNo+z];
  	npTags.add(tgs);
  }
  
  private void addLVC (String phrase, int offset, String[] tags, String[] tokens, int startTokNo, int mweTokens) {
  	String[] tagWindow = new String[mweTokens+4];
  	String[] tokenWindow = new String[mweTokens];
  	String[] orthographics = new String[mweTokens+4];
  	String[] tgs = new String[mweTokens];
  	int s = startTokNo - 2;
  	int e = startTokNo+mweTokens+2;
  	int i = 0;
  	while (s < e) {
  		if (s<0 || s>=(tokens.length-1)) {
  			tagWindow[i] = ".";
  			orthographics[i] = orthoNames[0];
  		} else {
  			tagWindow[i] = tags[s];
  			orthographics[i] = orthoNames[orthoClass(tokens[s])];
  		}
  		i++;
  		s++;
  	}
  	for (int j=0;j<mweTokens;++j) {
  		if (startTokNo+j < tokens.length) tokenWindow[j] = tokens[startTokNo+j]; else tokenWindow[j] = ".";
  	}
  	String vector = vectorMaker.svmVector(tagWindow, tokenWindow, orthographics);
  	double[] classProbs = {1,0};
  	if (learning) {
  	  classProbs = activeLearner.evaluate(vector, "lvcModel");  // confidence for each class. MWE should be class 1
  	}
  	// Accept NPC if classes as such, i.e. class 1 prob above threshold (default 0.5)
  	if (classProbs[0] <= thresholdLVC) return;
    lvcChunks.add(new String(phrase));
  	lvcPositions.add(offset);
  	lvcVectors.add(vector);
  	for (int z=0;z<mweTokens;++z) if (z+startTokNo < tags.length) tgs[z] = tags[startTokNo+z]; else tgs[z] = ".";
  	lvcTags.add(tgs);
  }
  
  private void addVPC (String phrase, int offset, String[] tags, String[] tokens, int startTokNo, int mweTokens) {
  	String[] tagWindow = new String[mweTokens+4];
  	String[] tokenWindow = new String[mweTokens];
  	String[] orthographics = new String[mweTokens+4];
  	String[] tgs = new String[mweTokens];
  	int s = startTokNo - 2;
  	int e = startTokNo+mweTokens+2;
  	int i = 0;
  	while (s < e) {
  		if (s<0 || s>=(tokens.length-1)) {
  			tagWindow[i] = ".";
  			orthographics[i] = orthoNames[0];
  		} else {
  			tagWindow[i] = tags[s];
  			orthographics[i] = orthoNames[orthoClass(tokens[s])];
  		}
  		i++;
  		s++;
  	}
  	for (int j=0;j<mweTokens;++j) {
  		tokenWindow[j] = tokens[startTokNo+j];
  	}
  	String vector = vectorMaker.svmVector(tagWindow, tokenWindow, orthographics);
  	double[] classProbs = {1,0};
  	if (learning) {
  	  classProbs = activeLearner.evaluate(vector, "vpcModel");  // confidence for each class. MWE should be class 1
  	}
  	// Accept NPC if classes as such, i.e. class 1 prob above threshold (default 0.5)
  	if (classProbs[0] <= thresholdVPC) return;
    if (phrase.endsWith(" \"")) phrase = phrase.substring(0, phrase.length()-2);
  	vpcChunks.add(new String(phrase));
  	vpcPositions.add(offset);
  	vpcVectors.add(vector);
  	for (int z=0;z<mweTokens;++z) if (z+startTokNo < tags.length) tgs[z] = tags[startTokNo+z]; else tgs[z] = ".";
    vpcTags.add(tgs);
  }  
  /*
	 * Chunk takes a sequence of tags and the corresponding tokens and parses the tags. Sequences of tags that 
	 * correspond to the bigram rules below are used to chunk the associated tokens into multi-word-expressions, and stored
	 */
  
	public void chunk (String posString, String tokenString) {
		chunk (posString, tokenString, null);
	}
	
	public void chunk (String posString, String tokenString, LinkedList<Integer>posPositions) {
		npChunks = new LinkedList<String>();
		vpcChunks = new LinkedList<String>();
		lvcChunks = new LinkedList<String>();
		pronouns = new LinkedList<String>();
		npPositions = new LinkedList<Integer>();
		vpcPositions = new LinkedList<Integer>();
		lvcPositions = new LinkedList<Integer>();
		npEndpoints = new LinkedList<Integer>();
		vpcEndpoints = new LinkedList<Integer>();
		lvcEndpoints = new LinkedList<Integer>();
		npTags = new LinkedList<String[]>();
		vpcTags = new LinkedList<String[]>();
		lvcTags = new LinkedList<String[]>();

		String tags = new String (posString+" .");
		String tokens = new String (tokenString+" .");
		StringTokenizer tagList;
		StringTokenizer tokenList;

		tagList = new StringTokenizer(tags, " \n");
		tokenList = new StringTokenizer(tokens, " \n");
		int tagTotal = tagList.countTokens();
		int tokenTotal = tokenList.countTokens(); 
		String[] tagArray = new String[tagTotal];
		String[] tokenArray = new String[tokenTotal];
		int k = 0;
    while (tokenList.hasMoreTokens()) {
    	String tag = tagList.nextToken();
    	if (tag2tagMap.containsKey(tag)) tagArray[k] = tag2tagMap.get(tag); else tagArray[k] = tag;
    	tokenArray[k] = tokenList.nextToken();
    	k++;
    }
    
  	Iterator<Integer> posIt = null;
		
		if (posPositions!=null) {
			posIt = posPositions.iterator();
		}
		
    // extract noun phrase chunks
		int offset = 0;
		int startPos = -1;
		int startTagNo = 0;
		int chunkTags = 0;

    for (int tokenNo=0;tokenNo<tokenTotal;++tokenNo) {
    	String pos = tagArray[tokenNo];
    	String word = tokenArray[tokenNo];
    	if (posIt!=null) {
      	if (posIt.hasNext()) {
      		offset = posIt.next();
      	}
      }
      nounPhraseEngine.stepEngine(pos, word, offset, tokenNo);
      // boolean typed = nounPhraseEngine.currentMweIsTyped(); // flag for subtyping - possible future use
      String extract = nounPhraseEngine.getExtractMWE();
      if (extract!=null) {
      	chunkTags = nounPhraseEngine.extractTokenCount;
      	startTagNo = tokenNo - chunkTags;
      	startPos = nounPhraseEngine.chunkStartPt();
      	addNPC(extract, startPos, tagArray, tokenArray, startTagNo, chunkTags);
      	if (nounPhraseEngine.hasEmbed()) {
      		String embeddedNPC = extract.substring(nounPhraseEngine.embedOffset());
      		int embedTagNo = startTagNo + nounPhraseEngine.embedTokenOffset();
      		int embedTags = chunkTags - nounPhraseEngine.embedTokenOffset();
      		addNPC(embeddedNPC, startPos+nounPhraseEngine.embedOffset(), tagArray, tokenArray, embedTagNo, embedTags);
      	}
      }
    }
    
		if (posPositions!=null) {
			posIt = posPositions.iterator();
		}
    for (int tokenNo=0;tokenNo<tokenTotal;++tokenNo) {
    	String pos = tagArray[tokenNo];
    	String word = tokenArray[tokenNo];
    	if (posIt!=null) {
      	if (posIt.hasNext()) {
      		offset = posIt.next();
      	}
      }
      verbPhraseEngine.stepEngine(pos, word, offset, tokenNo);
      boolean typed = verbPhraseEngine.currentMweIsTyped(); // flag for subtyping - possible future use
      String extract = verbPhraseEngine.getExtractMWE();
      if (extract!=null && !extract.equals("")) {
      	chunkTags = verbPhraseEngine.extractTokenCount;
      	startTagNo = verbPhraseEngine.chunkStartIndex();
      	startPos = verbPhraseEngine.chunkStartPt();
      	if (typed) {
          if (!defeatLVC(extract)) {
	          addLVC(extract, startPos, tagArray, tokenArray, startTagNo, chunkTags);
          }
      	}
      	else if (!defeatVPC(extract)) {
      	  addVPC(extract, startPos, tagArray, tokenArray, startTagNo, chunkTags);
      	}
      }
      if (pos.equals("PRP")) pronouns.add(new String(word));
    }

    filterVPCs();
	}

	public LinkedList<String> getNounChunks () {
		return npChunks;
	}
	
	public LinkedList<Integer> getNounChunkStarts () {
		return npPositions;
	}
	
	public LinkedList<Integer> getNounChunkEnds () {
		return npEndpoints;
	}
	
	public LinkedList<String> getNounMWEs () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
	    if (np.contains(" ")) mwes.add(np);
    }
		return mwes;
	}
	
	public LinkedList<Integer> getNounMWEstarts () {
		LinkedList<Integer> mwes = new LinkedList<Integer>();
	  Iterator<Integer> pit = npPositions.iterator();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
		  int position = pit.next();
	    if (np.contains(" ")) mwes.add(position);
    }
		return mwes;
	}
	
	public LinkedList<String[]> getNounMWEtags () {
		LinkedList<String[]> mwes = new LinkedList<String[]>();
	  Iterator<String[]> tit = npTags.iterator();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
		  String[] vec = tit.next();
	    if (np.contains(" ")) mwes.add(vec);
    }
		return mwes;
	}
	
	public LinkedList<String> getNounMWEvectors () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> vit = npVectors.iterator();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
		  String vec = vit.next();
	    if (np.contains(" ")) mwes.add(vec);
    }
		return mwes;
	}
	
	public LinkedList<String> getVerbMWEs () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> it = vpcChunks.iterator();
    while (it.hasNext()) {
		  String vpc = (String) it.next();
	    if (vpc.contains(" ")) mwes.add(vpc);
    }
		return mwes;
	}
	
	public LinkedList<String> getPhraseMWEs () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> it = lvcChunks.iterator();
    while (it.hasNext()) {
		  String vpc = (String) it.next();
	    if (vpc.contains(" ")) mwes.add(vpc);
    }
		return mwes;
	}
	
	public LinkedList<String> getVPCVectors () {
		return vpcVectors;
	}
	
	public LinkedList<String> getVPCChunks () {
		return vpcChunks;
	}
	
	public LinkedList<String[]> getVPCTags () {
		return vpcTags;
	}
	
	public LinkedList<Integer> getVPCChunkStarts () {
		return vpcPositions;
	}
	
	public LinkedList<Integer> getVPCChunkEnds () {
		return vpcEndpoints;
	}
	
	public LinkedList<String> getLVCVectors () {
		return lvcVectors;
	}
	
	public LinkedList<String> getLVCChunks () {
		return lvcChunks;
	}
	
	public LinkedList<String[]> getLVCTags () {
		return lvcTags;
	}
	
	public LinkedList<Integer> getLVCChunkStarts () {
		return lvcPositions;
	}
	
	public LinkedList<Integer> getLVCChunkEnds () {
		return lvcEndpoints;
	}
	
	public LinkedList<String> getPRPs () {
		return pronouns;
	}
	
  public String toString() {
	  Iterator<String> it = npChunks.iterator();
	  StringBuffer sb = new StringBuffer();
	  sb.append("{");
    while (it.hasNext()) {
  	  String np = (String) it.next();
    	if (np.contains(" ")) sb.append(np+",");
      }
    sb.append("}");
    
	  it = vpcChunks.iterator();
	  sb.append(",{");
    while (it.hasNext()) {
  	  sb.append(it.next()+",");
      }
    sb.append("}");

	  it = lvcChunks.iterator();
	  sb.append(",{");
    while (it.hasNext()) {
  	  sb.append(it.next()+",");
      }
    sb.append("}");
    return sb.toString();
  }

/*
 * getTagFile reads the specified file for a tag sequence in the form of "[DT][NN][VB][...."
 * Newlines, spaces, and square brackets are treated as delimiters, everything else is considered a tag
 * Tags should conform to Stanford POS tag set.
 */
  
  private String getTagFile (String inputFilename) {
    //System.err.println("Reading: "+inputFilename);
		FileReader docFileR;
		try {
			docFileR = new FileReader(inputFilename);
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			System.err.println ("File not found: "+inputFilename);
			return null;
		}
		StringBuffer buff = new StringBuffer();
		char c;
		
    try {
			int ch = docFileR.read();
	    if (ch==-1) {
	    	docFileR.close();
	    	return null;
	    }
	    boolean lastSpace = false;
			while (ch != -1) {
				c = (char) ch;
				if (c=='\r' || c=='[' || c== ']') {
					c= ' ';
					if (!lastSpace) {
						buff.append(c);
						lastSpace = true;
					}
				} else { 
				  lastSpace = false;
					buff.append (c);
				}
				ch = docFileR.read();
			}
			docFileR.close();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		return buff.toString();
	}

/*
 * getTokenFile reads the file specified by inputFilename and assumes the contents are a sequence of space-delimited tokens
 */
  
  private String getTokenFile (String inputFilename) {
		FileReader docFileR;
		try {
			docFileR = new FileReader(inputFilename);
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			System.err.println ("File not found: "+inputFilename);
			return null;
		}
		StringBuffer buff = new StringBuffer();
		char c;
		
    try {
			int ch = docFileR.read();
	    if (ch==-1) { 
	    	docFileR.close();
	    	return null;
	    }
			while (ch != -1) {
				c = (char) ch;
			  buff.append (c);
				ch = docFileR.read();
			}
			docFileR.close();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		return buff.toString();
	}

  /*
   * chunkTagAndTokenFiles reads the specified tagFile for a tag sequence in the form of "[DT][NN][VB][...."
   * Newlines, spaces, and square brackets are treated as delimiters, everything else is considered a tag
   * Tags should conform to Stanford POS tag set.
   * It reads the file tokenFile for the associated space or newline delimited tokens
   */
    
  public void chunkTagAndTokenFiles (String tagFile, String tokenFile) throws Exception {
  	String tags;
  	String tokens;
  	if ((tags=getTagFile(tagFile))==null) throw new Exception();
  	if ((tokens=getTokenFile(tokenFile))==null) throw new Exception();
  	chunk(tags, tokens);
  }

  private String[] getTestFiles () {
  	String [] resourceNames = {"",""};
  	ClassLoader classLoader = getClass().getClassLoader();
		File file = new File(classLoader.getResource("./resources/testTokens.txt").getFile());
		resourceNames[0] = file.getPath();
		file = new File(classLoader.getResource("./resources/testTags.txt").getFile());
		resourceNames[1] = file.getPath();
 	
  	return resourceNames;
  }

  public static void main(String args[]) {
		String tagsFilename = "";
		String textFilename = "";
		MweChunker chunker = new MweChunker();
		
		if (args.length == 1) {
			if (args[0].equals("--test")) {
				//Get file from resources folder
        String[] names = chunker.getTestFiles();
				tagsFilename = names[0];
				textFilename = names[1];
			}
		}		
		else if (args.length >= 2) {
			textFilename = args[0];
			tagsFilename = args[1];
		}
		else {
			System.err.println("No specified files");
			System.err.println("Usage: MweTester tokenFile tagFile");
			System.exit(-1);
		}
		
		try {
		  chunker.chunkTagAndTokenFiles(textFilename, tagsFilename);
		} catch (Exception e) {
			System.err.print("Failed to get tokens and/or tags from files: "+textFilename+" and "+tagsFilename);
			e.printStackTrace();
		}
		System.out.println(chunker.toString());
	}

}
	

