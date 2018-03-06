package wbif.sjx.RelateCells;

import ij.*;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import org.apache.commons.compress.compressors.FileNameUtil;
import org.apache.commons.io.FilenameUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import wbif.sjx.common.Object.Point;
import wbif.sjx.common.Object.Timepoint;
import wbif.sjx.common.Object.Track;
import wbif.sjx.common.Object.TrackCollection;
import wbif.sjx.common.Process.SwitchTAndZ;

import javax.swing.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.util.Arrays;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads tracks from results table.  Tracks are stored in terms of calibrated distances.
 */
public class ImportToResultsTable implements PlugIn {
    /**
     * Main method for debugging.
     * @param args
     */
    public static void main(String[] args) {
        new ImageJ();
        new ImportToResultsTable().run("");

    }

    /**
     * Run by ImageJ.
     * @param s
     */
    public void run(String s) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

            // Getting the file to read
            String filePath = Prefs.get("TrackAnalysis.filePath","");
            JFileChooser jFileChooser = new JFileChooser(filePath);
            jFileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            jFileChooser.showDialog(null,"Open");

            File inputFile = jFileChooser.getSelectedFile();
            Prefs.set("TrackAnalysis.filePath",inputFile.getAbsolutePath());

            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = null;
            documentBuilder = documentBuilderFactory.newDocumentBuilder();

            filePath = inputFile.getAbsolutePath();
            FileInputStream fileInputStream = new FileInputStream(filePath);
            Document doc = documentBuilder.parse(fileInputStream);
            doc.getDocumentElement().normalize();

            // Getting a list of experiment names
            NodeList experimentNodes = doc.getElementsByTagName("EXPERIMENT");
            String[] experimentNames = new String[experimentNodes.getLength()];
            for (int i=0;i<experimentNodes.getLength();i++) {
                String fullPathName = experimentNodes.item(i).getAttributes().getNamedItem("FILEPATH").getNodeValue();
                experimentNames[i] = FilenameUtils.getName(fullPathName);
            }

            // Displaying a dialog box to ask which experiment to load
            GenericDialog gd = new GenericDialog("Select an experiment");
            gd.addChoice("Experiment",experimentNames,experimentNames[0]);
            gd.showDialog();

            // Determining the experiment number
            String experimentName = gd.getNextChoice();
            int experimentToOpen = 0;
            for (int i=0;i<experimentNames.length;i++) {
                if (experimentNames[i].equals(experimentName)) {
                    experimentToOpen = i;
                    break;
                }
            }

            ResultsTable resultsTable = new ResultsTable();

            int row = 0;
            Node experimentNode = experimentNodes.item(experimentToOpen);
            for (int i=0;i<experimentNode.getChildNodes().getLength();i++) {
                Node trackNode = experimentNode.getChildNodes().item(i);
                int ID = Integer.parseInt(trackNode.getAttributes().getNamedItem("ID").getNodeValue());

                for (int j=0;j<trackNode.getChildNodes().getLength();j++) {
                    Node pointNode = trackNode.getChildNodes().item(j);
                    double x = Double.parseDouble(pointNode.getAttributes().getNamedItem("X").getNodeValue());
                    double y = Double.parseDouble(pointNode.getAttributes().getNamedItem("Y").getNodeValue());
                    double f = Double.parseDouble(pointNode.getAttributes().getNamedItem("FR").getNodeValue());

                    resultsTable.setValue("ID",row,ID);
                    resultsTable.setValue("X",row,x);
                    resultsTable.setValue("Y",row,y);
                    resultsTable.setValue("Z",row,0);
                    resultsTable.setValue("FRAME",row,f);

                    row++;

                }
            }

            resultsTable.show("RelateCells result");

        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException | ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        }
    }
}
