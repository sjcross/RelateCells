package wbif.sjx.RelateCells;

import ij.gui.PolygonRoi;

import java.awt.*;
import java.util.ArrayList;

class Cell extends PolygonRoi {
    private int trackID = -1;
    private Color colour = Color.getHSBColor(1,1,1);
    private ArrayList<Cell> linkedCells = new ArrayList<>();
    private double meanIntensity = 0;


    // CONSTRUCTORS

    Cell(float[] xPoints, float[] yPoints, int type) {
        super(xPoints, yPoints, type);
    }

    Cell(Polygon p, int type) {
        super(p, type);
    }


    // PUBLIC METHODS
    /**
     * Measures the closest edge-edge separation between the cell and a second, user-specified cell
     */
    double measureDistanceToCell(Cell cell2) {
        float[] x1 = getFloatPolygon().xpoints;
        float[] y1 = getFloatPolygon().ypoints;
        float[] x2 = cell2.getFloatPolygon().xpoints;
        float[] y2 = cell2.getFloatPolygon().ypoints;

        double minDist = Double.MAX_VALUE;
        for (int i=0;i<x1.length;i++) {
            for (int j=0;j<x2.length;j++) {
                double dist = Math.sqrt((x2[j]-x1[i])*(x2[j]-x1[i])+(y2[j]-y1[i])*(y2[j]-y1[i]));
                if (dist < minDist) {
                    minDist = dist;
                }
            }
        }

        return minDist;

    }

    void addLinkedCell(Cell cell) {
        linkedCells.add(cell);

    }


    // GETTERS AND SETTERS

    int getTrackID() {
        return trackID;
    }

    void setTrackID(int trackID) {
        this.trackID = trackID;
    }

    Color getColour() {
        return colour;
    }

    void setColour(Color colour) {
        this.colour = colour;
    }

    ArrayList<Cell> getLinkedCells() {
        return linkedCells;
    }

    public double getMeanIntensity() {
        return meanIntensity;
    }

    public void setMeanIntensity(double meanIntensity) {
        this.meanIntensity = meanIntensity;
    }
}
