/**
 * @file LogParser.java
 * @authors Leah Talkov, Jerry Tsui
 * @date 8/3/2016
 * Parses through the given logfile and generates error entries based
 * on the errors it finds, and the keywords that the user has specified. 
 * Different errors will be created depending on the method the user
 * is using to find them. 
 */
package interfaceTest;

import java.awt.Component;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;


public class LogParser {
	/**Headers for the JTable*/
	protected final String [] headers = {"Error #", "Timestamp",
			"Keywords", "Error Message", "Suggested Solution"};
	/**Contains the entries that are generated by the errors found*/
	protected List<Object[]> errorData = new ArrayList<Object[]>();
	/**Contains headers and errorData, used to generate contents for the JTable*/
	private Object [][] data;
	/**Keeps track of how many errors have been found*/
	private int errorCount;
	/**The amount of bytes we have parsed through*/
	private long progress;
	/**The percent we have calculated using progress and the file size*/
	private int percent;
	/**The current percent that we are on*/
	private int oldPercent;
	/**The tab index that the user has open on the interface*/
	private int selectedTab;
	/**A String to hold a line from the given logFile*/
	protected String logLine;
	/**Holds the individual entries from logLine, split by " "*/
	protected String[] logWords;
	/**Object that holds an individual generated entry*/
	private Object[] entry;
	/**LogicEvaluator used if the user wants to refine their search*/
	private LogicEvaluator logicEvaluator;
	/**UserView object that contains Swing objects that
	   will be changed by functions in this class*/
	protected FixedStack<String> linesBefore;
	protected ConcurrentHashMap<Integer, FixedStack<String>> linesAfter;
	
	protected static UserView view;
	
	public LogParser(UserView view, int tab) {
		selectedTab = tab;
		LogParser.view = view;
		logicEvaluator = new LogicEvaluator(this);
		
		linesBefore = new FixedStack<String>(view.numLinesBefore + 1);
		linesAfter = new ConcurrentHashMap<Integer, FixedStack<String>>();
		
		
		//If not null, we set the arrayLists in logicEval
		if(view.keyWordArrayList != null)
			logicEvaluator.setkeyWords(view.keyWordArrayList);
		if(view.operandArrayList != null)
			logicEvaluator.setOperands(view.operandArrayList);
		if(view.notArrayList != null)
			logicEvaluator.setHasNot(view.notArrayList);
		if (tab == 1)
			logicEvaluator.addORs();
	}
	
	/**
	 * Parses through the file and generates the errors. It also
	 * makes calls to the ProgressDialog object to see how far through the file
	 * we are. Different errors will be made depending on how the user is 
	 * searching, either through checkbox or through a refined logic search. 
	 * @param file The logfile that the user wants to parse through
	 * @param pd A create progress dialog object that keeps track of the parsing progress
	 * @throws IOException
	 */
	void parseErrors(File file, ProgressDialog pd) throws IOException {
		view.updateKeyWords(selectedTab);
		view.linesBeforeArrayList.clear();
		view.linesAfterHashMap.clear();
		
		percent = 0;
		oldPercent = 0;
		errorData.clear();
		errorCount = 0;
		progress = 0;
		String timeStamp = null;
		StringBuilder errorMessage = new StringBuilder();
		boolean keywordFound = false;
		boolean timeStampFound = false;
		boolean specialCase = false;
		
		FileReader logInput = new FileReader(view.logFile);
		BufferedReader logbr = new BufferedReader(logInput);

		logLine = logbr.readLine();
		//Timer for performance testing
		long startTime = System.nanoTime();
		while(logLine != null) {
			linesBefore.push(logLine);
			updateLinesAfter(logLine);
			updateProgress(logLine);
			//If the user is on the logic statement tab, we send 
			//lines to the logic evaluator
			if(selectedTab == 1) {
				logicEvaluator.addLines(logLine, logbr);
			}
			else {
				logWords = logLine.split(" ");
				timeStamp = null;
				errorMessage.setLength(0);
				entry = null;
				
				keywordFound = false;
				timeStampFound = false;
				specialCase = false;
				
				for (String testWord : logWords) {
					//Timestamp will always come first
					//Is this a reliable way to find timestamp?
					//Maybe change to regex
					if (testWord.length() == 19 && !timeStampFound) {
						timeStamp = testWord;
						timeStampFound = true;
					}
					if(timeStampFound && !keywordFound) {
						//Testing the UCode from the file against the error UCodes
						if (view.keyWords.contains(testWord)) {
							keywordFound = true;
							errorCount++;
							
							//If we have a deadlock error, this is a special case
							if (testWord.equals("DEADLOCK")) {
								entry = parseDeadlockError(logbr, timeStamp);
								specialCase = true;
								break;
							}
							//If we have an arrow error, this is a special case
							else if (testWord.equals("===>") && logLine.contains("Time critical")) {
								entry = parseArrowError(logbr, timeStamp, logWords);
								specialCase = true;			
								break;
							}
							//Otherwise we make an entry like normal
							else {
								addLinesAfter();
								entry = new Object[5];
								entry[0] = errorCount;
								entry[1] = timeStamp;
								entry[2] = testWord;
					
								//We get a suggested solution for the corresponding keyword
								if (view.solutions.get(entry[2]) != null) {
									entry[4] = view.solutions.get(entry[2]);
								}
							}
						}
					}
					//If we've found both the timestamp and the keyword, we start
					//generating an error message until the line is done
					else if(timeStampFound && keywordFound) {
						errorMessage.append(testWord + " ");
					}
				}
				//Make sure an entry was actually created for the line
				if(entry != null) {
					if (!specialCase) {
						addLinesBefore();
						entry[3] = errorMessage.toString();
					}
					//If there was no error message, then set to blank
					if (entry[3] == null) {
						entry[3] = " ";
					}
					
					errorData.add(entry);
				}
			}
			logLine = logbr.readLine();
		}
		
		System.out.println("Size of Arraylist:" + view.linesBeforeArrayList.size());
		//We make entries out of the errors that we've found in logic eval
		logicEvaluator.makeEntries();
		//Logs execution time to the console
		long endTime = System.nanoTime();
		long duration = (endTime - startTime)/1000000;
		System.out.println("Operation completed in " + duration + " ms");
		
		//If we are doing the logic statement, then we get the error count from there
		if(selectedTab == 1) {
			errorCount = logicEvaluator.getErrorCount();
		}
		view.dialog.doneParse(errorCount);
		logbr.close();
		//We fill the data object with our entries for the resulting table
		data = new Object[errorData.size()][];
		for(int i = 0; i < errorData.size(); i++) {
			data[i] = errorData.get(i);
		}
		view.menuItemLinesBefore.setEnabled(true);
		view.menuItemLinesAfter.setEnabled(true);
		makeTable();
	}
	
	/**
	 * Creates the entry for an arrow error, a special case since
	 * arrow errors can be made of multiple lines in a logfile
	 * @param logbr The buffered reader reading through the logfile
	 * @param timeStamp The timestamp of the line where the first arrow was encountered
	 * @param currArray An array containing the elements of the above line, split by " "
	 * @return Returns an entry that can be added to errorData
	 * @throws IOException
	 */
	Object[] parseArrowError(BufferedReader logbr, String timeStamp, String[] currArray) throws IOException {
		addLinesBefore();
		Object[] tempEntry = new Object[5];
		String[] words;
		tempEntry[0] = errorCount;
		tempEntry[1] = timeStamp;
		tempEntry[2] = "===>";
		int arrowindex = 0;  
        boolean closingArrowTagFound = false;
        boolean outsideTimeStampBounds = false;
        StringBuilder errorMsg = new StringBuilder();
        
        //If the time of this arrow error is outside the bounds
        //specified by the user, this is an invalid entry
        if (!compareTimeStamp(currArray)) {
			outsideTimeStampBounds = true;
		}
        //We fill the entry with the solution
		if (view.solutions.get(tempEntry[2]) != null) {
			tempEntry[4] = view.solutions.get(tempEntry[2]);
		}
		//We find the index where the arrow starts, so we make the error message
		//on each subsequent line start at that index
		for (int i=0; i<currArray.length; i++) {
			if (currArray[i].equals("===>")) {
				arrowindex = i-1;
				for (int j=(i+1); j<currArray.length; j++) {
					errorMsg.append(currArray[j] + " ");
				}
				break;
			}
		}
		String firstLineOfError = errorMsg.toString();
		logLine = logbr.readLine();
		while (!closingArrowTagFound && logLine != null) {
			linesBefore.push(logLine);
			updateLinesAfter(logLine);
			updateProgress(logLine);
			words = logLine.split(" ");
			if (logLine.contains("===>")){
				//This is the case for a single arrow, and we
				//do a recursive call
				if (logLine.contains("Time critical")) {
					errorCount++;
					tempEntry[3] = firstLineOfError;
					String[] tempArray = logLine.split(" ");
					String tempTimeStamp = "";
					for (String s : tempArray) {
						if (s.length() == 19) {
							tempTimeStamp = s;
							break;
						}
					}
					errorData.add(parseArrowError(logbr, tempTimeStamp, tempArray));
					return tempEntry;
				}
				//This is where we append the error message of the current line
				if (arrowindex < words.length) {
					for (int i = arrowindex; i < words.length; i++) {
						errorMsg.append(words[i] + " ");
					}
				}
				else {
					errorMsg.append(logLine + " ");
				}
				closingArrowTagFound = true;
				break;
			}
			if (arrowindex < words.length) {
				for (int i = arrowindex; i < words.length; i++) {
					errorMsg.append(words[i] + " ");
				}
			} 
			else {
				errorMsg.append(logLine + " ");
			}
			if (!closingArrowTagFound) {
				logLine = logbr.readLine();
			}
		}
		tempEntry[3] = errorMsg.toString();
		if (outsideTimeStampBounds) {
			errorCount--;
			return null;
		}
		addLinesAfter();
		return tempEntry;
	}
	
	/**
	 * Creates the entry for an DEADLOCK error, a special case since
	 * DEADLOCK errors can be made of multiple lines in a logfile
	 * @param logbr The Buffered Reader reading through the logfile
	 * @param timeStamp Timestamp of the line where DEADLOCK first occurred
	 * @return Return an entry that can be added to errorData
	 * @throws IOException
	 */
	Object[] parseDeadlockError(BufferedReader logbr, String timeStamp) throws IOException {
	   addLinesBefore();
	   addLinesAfter();
       Object[] entry = new Object[5];
       ArrayList <String> errorLines = new ArrayList<String>();
       boolean matchingDeadlock = false;
       StringBuilder testLine = new StringBuilder();
       StringBuilder errorMsg = new StringBuilder();
       String[] words;
       entry[0] = errorCount;
       entry[1] = timeStamp;
       entry[2] = "DEADLOCK";
       String Line = logbr.readLine();
       while(!matchingDeadlock && Line != null) {
    	  linesBefore.push(logLine);
    	  updateLinesAfter(logLine);
          boolean timeStampFound = false;
          boolean uCodeFound = false;              
          testLine.setLength(0);
          updateProgress(logLine);
          words = Line.split(" ");
          for(String testWord : words) {
        	  if(!timeStampFound && testWord.length() == 19) {
        		  timeStampFound = true;
                  //If our timestamps are not equal, we 
                  //don't have a matching deadlock
                  if(timeStampDifference(testWord, timeStamp)) {
                	  entry[3] = " ";
                      if (view.solutions.get(entry[2]) != null)
                    	  entry[4] = view.solutions.get(entry[2]);
                          return entry;
                      }
                  }
        	  	  //Now we need to find the U-code to know when to start the error message
                  else if(!uCodeFound && timeStampFound){
                	  if(testWord.length() > 2) {
                		  if(testWord.charAt(0) == 'U' && Character.isDigit(testWord.charAt(1))){
                			  uCodeFound = true;
                          }
                      }
                  }
        	  	  //If we've found both, start appending to the error message
                  else if(uCodeFound && timeStampFound) {
                	  if(testWord.equals("DEADLOCK")) {
                		  matchingDeadlock = true;
                          for(int i = 0; i < errorLines.size(); i++) {
                        	  errorMsg.append(errorLines.get(i));
                          }
                          entry[3] = errorMsg.toString();
                          if (view.solutions.get(entry[2]) != null)
                        	  entry[4] = view.solutions.get(entry[2]);
                          return entry;
                          }
                       else
                    	   testLine.append(testWord + " ");  
                 }      
           }
              errorLines.add(testLine.toString());
              Line = logbr.readLine();
       }
       return entry;
    }

	/**
	 * Makes and fills a TableModel for the JTable in AdminView after parsing 
	 * has been completed. 
	 */
	void makeTable() {
		DefaultTableModel tableModel = new DefaultTableModel(data, headers) {
		    @Override
		    public boolean isCellEditable(int row, int column) {
		       //all cells false
		       return false;
		    }
		};
		
		view.errorTable = new JTable(tableModel) {
			//Renders each columnn to fit the data
			public Component prepareRenderer(TableCellRenderer renderer, int row, int column) 
			{
				Component component = super.prepareRenderer(renderer, row, column);
				int rendererWidth = component.getPreferredSize().width;
	           TableColumn tableColumn = getColumnModel().getColumn(column);
	           tableColumn.setPreferredWidth(Math.max(rendererWidth + getIntercellSpacing().width, tableColumn.getPreferredWidth()));
	           return component;
			}
		};
		view.errorTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		view.errorScrollPane.setViewportView(view.errorTable);
		view.errorTable.setCellSelectionEnabled(true);
		view.errorTable.addMouseListener(new TableMouseListener(view.errorTable));
		view.errorTable.setComponentPopupMenu(view.popupMenu);
	}
	
	/**
	 * Called in parseDeadlockError, checks to see if the time stamp 
	 * difference in substantial enough to warrant DEADLOCKs being 
	 * two separate instances
	 * @param testStamp The time stamp of the tested DEADLOCK
	 * @param timeStamp The time stamp of the original DEADLOCK
	 * @return True if the difference is considered valid, false otherwise
	 */
	boolean timeStampDifference(String testStamp, String timeStamp){
           int i1 = Integer.parseInt(testStamp.substring(16));
           int i2 = Integer.parseInt(timeStamp.substring(16));
           if (i1 > i2) {
                  if((i1 - i2) > 20)
                        return true;
                  else
                        return false;
           }
           else {
                  if((i2 - i1) > 20)
                        return true;
                  else 
                        return false;
           }
    }
    

	/**
	 * Updates the progress on the ProgressDialog by dividing
	 * progress (the lines read so far) by the total file size. The progress
	 * bar is only updated if the generated percent (integer of the division)
	 * is greater than oldPercent.
	 */
	void updateProgress(String addLine){
		progress += addLine.length();
		percent = (int) (progress / view.fileSizeDivHundred);
		if (percent > oldPercent){
			view.dialog.updateProgress(percent);
			oldPercent = percent;
		}
	}
	
	/**
	 * Compares the time of a Time Critical arrow error to see if
	 * it is within the bounds the user designated in the interface.
	 * @param line The line where Time Critical occurred
	 * @return True if the time is within the bounds, false otherwise
	 */
	boolean compareTimeStamp(String[] line){
		String time = line[(line.length-1)].replaceAll("[.]", "");
		time = time.replaceAll("\'", "");
		time = time.replace(":", ".");
		double t = Double.parseDouble(time);
		return ((t >= view.lowerBound) && (t <= view.upperBound));
	}
	
	void addLinesBefore(){
		ArrayList<String> arrayList = new ArrayList<String>();
		for (String str : linesBefore){
			arrayList.add(str);
		}
		arrayList.remove(arrayList.size()-1);
		view.linesBeforeArrayList.add(arrayList);
	}
	
	void addLinesAfter(){
		linesAfter.put(errorCount, new FixedStack<String>(view.numLinesAfter + 1));
	}
	
	void updateLinesAfter(String line){
		for (Map.Entry<Integer, FixedStack<String>> entry : linesAfter.entrySet()){
			entry.getValue().push(line);
			if (entry.getValue().isFull()){
				ArrayList<String> arrayList = new ArrayList<String>();
				for (String str : entry.getValue()){
					arrayList.add(str);
				}
				arrayList.remove(arrayList.size()-1);
				view.linesAfterHashMap.put(entry.getKey(), arrayList);
				linesAfter.remove(entry.getKey());
			}
		}
	}
}
