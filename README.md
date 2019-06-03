[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.1422439.svg)](https://zenodo.org/record/1422439)

Installation
------------
- The latest version of the plugin can be downloaded from the [Releases](https://github.com/SJCross/RelateCells/releases) page.
- Place this .jar file into the /plugins directory of the your Fiji installation. 
- It's necessary to manually install the [StackReg_ plugins](http://bigwww.epfl.ch/thevenaz/stackreg/) that are used for image registration.  This can be done by enabling the BIG-EPFL update site in the [Fiji updater](https://imagej.net/Updater).


Starting the plugin
-------------------
- In Fiji, run the plugin from Plugins > Wolfson Bioimaging > RelateCells > RelateCells
- You'll be presented with a parameters dialog:
  - "Fluorescence channel name" is the unique part of the filename corresponding to fluorescence channel images.  For example, in the name "180216_Red_B1_4.avi" this is simply "Red".
  - "Phase contrast channel name" is the unique part of the filename corresponding to phase-contrast channel images.  For example, in the name "180216_Phase_B1_4.avi" this is simply "Phase".
  - "Measure phase contrast fluorescence" is an option allowing fluorescence intensity coincident with the phase contrast cells to be measured.  If enabled, the unique filename is specified as above.
  - "Border width (%)" allows for a border region to be ignored.  This is necessary to remove accidental detection at the image edge following image registration.
  - "DoG filter radius (px)" is the sigma (radius) for the difference of gaussian filter run on the phase contrast channel.  It should be set to match the approximate cell radius.
  - "Threshold multiplier" allows the fluorescence cell detection threshold to be shifted up or down relative to the automatically-identified threshold value.
  - "Minimum cell size (px^2)" and "Maximum cell size (px^2)" allow small or large objects to be ignored from fluorescence cell identification.
  - "Max. edge-edge distance (px)" is the maximum spatial linking distance for fluorescence and phase-contrast cells.
  - "Max. centroid-centroid distance (px)" is the maximum spatial linking distance for tracking fluorescence cells in time.
  - "Detection radius (px)" and "Threshold" correspond to the spot detection parameters in the TrackMate LoG detector.
- The current plugin progress is displayed in the log window.  Once the analysis has run, "Complete" will be displayed.


Note
----
This plugin is still in development and test coverage is currently incomplete.  Please keep an eye on results and add an [issue](https://github.com/SJCross/RelateCells/issues) if any problems are encountered.


Acknowledgements
----------------
A list of bundled dependencies along with their respective licenses can be found [here](https://cdn.statically.io/gh/SJCross/TrackAnalysis/cd9fb994/target/site/dependencies.html).
