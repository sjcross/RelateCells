package wbif.sjx.RelateCells;

import wbif.sjx.common.MathFunc.CumStat;
import wbif.sjx.common.Object.HCMetadata;

class Result extends HCMetadata {
    double[] x = new double[0];
    double[] y = new double[0];
    double[] frame = new double[0];
    double[] nLinks = new double[0];
    double[] nLinksNorm = new double[0];
    private int trackID = -1;
    private double meanInteractionDuration = -1;
    private double stdInteractionDuration = -1;


    void setTrackID(int trackID) {
        this.trackID = trackID;
    }

    void setX(double[] x) {
        this.x = x;
    }

    void setY(double[] y) {
        this.y = y;
    }

    void setFrame(double[] frame) {
        this.frame = frame;
    }

    void setnLinks(double[] nLinks) {
        this.nLinks = nLinks;
    }

    void setnLinksNorm(double[] nLinksNorm) {
        this.nLinksNorm = nLinksNorm;
    }

    int getTrackID() {
        return trackID;

    }

    void calculateInteractionDuration() {
        CumStat cs = new CumStat();

        int currDur = 0;
        for (double link:nLinks) {
            if (link == 0) {
                if (currDur != 0) {
                    // Terminates the current run
                    cs.addMeasure(currDur);
                    currDur = 0;
                }

            } else {
                // This is still a non-zero interaction run
                currDur++;

            }
        }

        // Adding the final count
        if (currDur != 0) {
            cs.addMeasure(currDur);

        }

        // Assigns the relevant variables
        meanInteractionDuration = cs.getMean();
        stdInteractionDuration = cs.getStd();

    }

    double getMeanInteractionDuration() {
        // Checks if the relevant statistic has already been calculated.  If not, it runs it
        if (meanInteractionDuration == -1) {
            calculateInteractionDuration();
        }

        return meanInteractionDuration;

    }

    double getStdInteractionDuration() {
        // Checks if the relevant statistic has already been calculated.  If not, it runs it
        if (stdInteractionDuration == -1) {
            calculateInteractionDuration();
        }

        return stdInteractionDuration;

    }
}