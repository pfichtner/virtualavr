bool handleCustomMessage(String customId, String value) {
    // here you can write your own code. 
  return false;
}

bool handleKprs(const String& params, size_t length) {
  // here you can write your own code. For instance the commented code change pin intensity if you press 'a' or 's'
  // take the command and change intensity on pin 11 this is needed just as example for this sketch
  
//  static int intensity = 0;
//  char commandChar = params.charAt(3);
//  if (commandChar == 'a') { // If press 'a' less intensity
//    intensity = max(0, intensity - 1);
//    analogWrite(11, intensity );
//    return true;
//   } else if (commandChar == 's') { // If press 's' more intensity
//    intensity = min(125, intensity + 1);
//    analogWrite(11, intensity );
//    return true;
//  }
  return false;
}

