package org.iinegve.sftp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Util {

  public static <T> List<T> list(T... values) {
    ArrayList<T> list = new ArrayList<>();
    Collections.addAll(list, values);
    return list;
  }
}
