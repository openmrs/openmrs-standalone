package org.openmrs.standalone;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.util.Calendar;

import javax.swing.JTextArea;

public class TextAreaWriter extends FilterOutputStream {
	
	private JTextArea text;
	
	private int length;
	
	public TextAreaWriter(JTextArea text) {
		super(new ByteArrayOutputStream());
		this.text = text;
	}
	
	public void write(byte b[]) throws IOException {
		String aString = new String(b);
		text.append(aString);
	}
	
	public void write(byte b[], int off, int len) throws IOException {
		length += len;
		String aString = new String(b, off, len);
		text.append(aString);
		
		//Not to run out memory, start trimming the oldest characters whenever we reach
		//a particular threshold.
		if (length > 10000) {
			text.setText(text.getText().substring(len));
			length = length - len;
		}
		
		//scroll to the bottom.
		text.setCaretPosition(length);
		
		//Add to the log file under currentdir/tomcat/logs
		try {
			Calendar cal = Calendar.getInstance();
			String fileName = cal.get(Calendar.DATE) + "-" + cal.get(Calendar.MONTH) + 1 + "-" + cal.get(Calendar.YEAR)
			        + ".log";
			String path = new File(fileName).getAbsolutePath();
			path = path.substring(0, path.lastIndexOf(File.separatorChar) + 1) + "tomcat" + File.separatorChar + "logs";
			new File(path).mkdirs();
			FileWriter aWriter = new FileWriter(path + File.separatorChar + fileName, true);
			aWriter.write(aString);
			aWriter.close();
		}
		catch (Exception ex) {

		}
	}
}
