package cd.mbsc.finapp;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * Écran du scanner de QR (zxing-android-embedded), déclaré à part dans le manifeste pour le tenir en portrait :
 * l'écran de capture par défaut de la bibliothèque est en paysage.
 */
public class Scanner extends CaptureActivity { }
