package wolfson.RelateCells

import ij.IJ
import ij.ImageJ
import ij.plugin.ChannelSplitter
import ij.plugin.Duplicator
import ij.plugin.ImageCalculator
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.ImageMath
import ij.process.ImageConverter

/**
 * Created by sc13967 on 14/03/2017.
 */
class Main {
    // Getting open image
    static void main(String[] args) {
        // Parameters
        def logR = 5

        // Starting ImageJ
        new ImageJ()
        IJ.runMacro("waitForUser")

        // Getting the image open in ImageJ
        def ipl = IJ.getImage()

        def ipls = new ChannelSplitter().split(ipl)

        def phase1 = ipls[1]
        def phase2 = new Duplicator().run(phase1)

        new ImageConverter(phase1).convertToGray32()
        new ImageConverter(phase2).convertToGray32()

        // PROBLEM WITH THIS START
        ImageMath.applyMacro(phase1.processor,"\"Multiply... value=4\"",false)
        phase1.show()
        // PROBLEM WITH THIS END

        new GaussianBlur().blurGaussian(phase1.processor,4,4,0.01)
        new GaussianBlur().blurGaussian(phase2.processor,5,5,0.01)

        def phaseSub = new ImageCalculator().run("Subtract create 32-bit",phase2,phase1)

        phaseSub.show()

    }
}

//Img<IntType> img = ImageJFunctions.wrap(ipl)
//
//// Getting the phase-contrast channel
//def pcImg = Views.hyperSlice(img,2,1)
//pcImg = Views.hyperSlice(pcImg,2,1)
//
//long[] dims = new long[pcImg.numDimensions()]
//pcImg.dimensions(dims)
//
//def imgFac = new CellImgFactory(2)
//def imgSmall = imgFac.create(dims,new FloatType())
//
//Gauss3.gauss(4,pcImg,imgSmall)
//ImageJFunctions.show(imgSmall)