package tech.datatype;

import java.nio.IntBuffer;

public interface MutableRemove extends IOBase
{
  void remove(int idx);
  void removeRange(int idx, int count);
}
