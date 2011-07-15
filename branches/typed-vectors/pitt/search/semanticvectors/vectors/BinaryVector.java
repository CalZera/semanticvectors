package pitt.search.semanticvectors.vectors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Logger;

import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.OpenBitSet;

/**
 * Binary implementation of Vector.
 *
 * @author cohen
 */
public class BinaryVector extends Vector {
  public static final Logger logger = Logger.getLogger(BinaryVector.class.getCanonicalName());

  private final int dimensions;

  /**
   * Elemental representation for binary vectors. 
   */
  private OpenBitSet bitSet;
  private boolean isSparse;

  /** 
   * Representation of voting record for superposition. Each OpenBitSet object contains one bit
   * of the count for the vote in each dimension. The count for any given dimension is derived from
   * all of the bits in that dimension across the OpenBitSets in the voting record.
   * 
   * The precision of the voting record (in decimal places) is defined upon initialization.
   * By default, if the first weight added is an integer, rounding occurs to the nearest integer
   * Otherwise, rounding occurs to the second decimal place.
   */ 
  private ArrayList<OpenBitSet> votingRecord;
  // TODO(widdows) Understand and comment this.
  private OpenBitSet tempSet;
  int decimalPlaces = 0;
  // TODO(widdows) Comment this. Understand why it's an int rather than a list of weights
  // for each element of the voting record.
  int actualVotes = 0;
  // TODO(widdows) Understand and comment this.
  int minimum = 0;

  public BinaryVector(int dimensions) {
    this.dimensions = dimensions;
    this.bitSet = new OpenBitSet(dimensions);
    this.isSparse = true;
  }

  /**
   * Returns a new copy of this vector, in dense format.
   */
  @SuppressWarnings("unchecked")
  public BinaryVector copy() {
    BinaryVector copy = new BinaryVector(dimensions);
    copy.bitSet = (OpenBitSet) bitSet.clone();
    if (!isSparse)
      copy.votingRecord = (ArrayList<OpenBitSet>) votingRecord.clone();
    return copy;
  }

  public String toString() {
    StringBuilder debugString = new StringBuilder("BinaryVector.");
    int printLength = Math.min(dimensions, 20);
    if (isSparse) {
      debugString.append("  Elemental.  First " + printLength + " values are:\n");
      for (int x = 0; x < printLength; x++) debugString.append(bitSet.getBit(x) + " ");
      debugString.append("\nCardinality " + bitSet.cardinality()+"\n");
    }
    else {
      debugString.append("  Semantic.  First " + printLength + " values are:\n");
      // TODO - output count from first 20 dimensions
      debugString.append("NORMALIZED: ");
      for (int x = 0; x < printLength; x++) debugString.append(bitSet.getBit(x) + " ");
      debugString.append("\n");

      // Calculate actual values for first 20 dimensions
      double[] actualvals = new double[20];
      debugString.append("COUNTS    : ");

      for (int x =0; x < votingRecord.size(); x++) {
        for (int y = 0; y < printLength; y++) {
          if (votingRecord.get(x).fastGet(y)) actualvals[y] += Math.pow(2, x); 
        }
      }
      
      for (int x = 0; x < printLength; x++) {
        debugString.append((int) ((minimum + actualvals[x]) / Math.pow(10, decimalPlaces)) + " ");
      }
      debugString.append("\nCardinality " + bitSet.cardinality()+"\n");
      debugString.append("Votes " + actualVotes+"\n");
      debugString.append("Minimum "+minimum+"\n");
    }
    return debugString.toString();
  }

  @Override
  public int getDimensions() {
    return dimensions;
  }

  public BinaryVector createZeroVector(int dimensions) {
    return new BinaryVector(dimensions);
  }

  @Override
  public boolean isZeroVector() {
    if (isSparse) {
      return bitSet.cardinality() == 0;
    } else {
      return (votingRecord == null) || (votingRecord.size() == 0);
    }
  }

  @Override
  /**
   * Generates a basic elemental vector with a given number of 1's and otherwise 0's.
   *
   * @return representation of basic binary vector.
   */
  public BinaryVector generateRandomVector(int dimensions, int numEntries, Random random) {
    BinaryVector randomVector = new BinaryVector(dimensions);
    randomVector.bitSet = new OpenBitSet(dimensions);
    int testPlace = dimensions - 1, entryCount = 0;

    // Iterate across dimensions of bitSet, changing 0 to 1 if random(1) > 0.5
    // until dimension/2 1's added.
    while (randomVector.bitSet.cardinality() < numEntries) {	
      testPlace = random.nextInt(dimensions);
      if (!randomVector.bitSet.fastGet(testPlace)) {
        randomVector.bitSet.fastSet(testPlace);
        entryCount++;	
      }
    }
    return randomVector;
  }

  @Override
  /**
   * Measures overlap of two vectors using 1 - normalized Hamming distance
   * 
   * Causes this and other vector to be converted to dense representation.
   */
  public double measureOverlap(Vector other) {
    IncompatibleVectorsException.checkVectorsCompatible(this, other);
    if (isZeroVector()) return 0;
    BinaryVector binaryOther = (BinaryVector) other;
    if (binaryOther.isZeroVector()) return 0;

    // Calculate hamming distance in place using cardinality and XOR, then return bitset to
    // original state.
    this.bitSet.xor(binaryOther.bitSet);
    double hammingDistance = this.bitSet.cardinality();
    this.bitSet.xor(binaryOther.bitSet);
    return 1 - (hammingDistance / (double) dimensions);
  }

  @Override
  /**
   * Adds the other vector to this one. If this vector was an elemental vector, the 
   * "semantic vector" components (i.e. the voting record and temporary bitset) will be
   * initialized
   * 
   * Note that the precision of the voting record (in decimal places) is decided at this point:
   * if the initialization weight is an integer, rounding will occur to the nearest integer
   * 
   * If not, rounding will occur to the second decimal place.
   * 
   * This is an attempt to save space, as voting records can be prohibitively expansive
   * if not contained.
   */
  public void superpose(Vector other, double weight, int[] permutation) {
    IncompatibleVectorsException.checkVectorsCompatible(this, other);
    BinaryVector realOther = (BinaryVector) other;
    if (isSparse) {
      if (Math.round(weight) != weight) {
        decimalPlaces = 2; 
      } 
      elementalToSemantic();
    }

    if (permutation != null) {
      // TODO: allow for permutation of incoming vector (i.e. generate new vector with appropriate
      // permutations and add this instead).
      logger.warning("Permutation for binary vectors is not yet implemented.");
    }

    superpose(realOther.bitSet, weight);
  }

  /**
   * This method is the first of two required to facilitate superposition. The underlying representation
   * (i.e. the voting record) is an ArrayList of OpenBitSet, each with dimension "dimension", which can
   * be thought of as an expanding 2D array of bits. Each column keeps count (in binary) for the respective
   * dimension, and columns are incremented in parallel by sweeping a bitset across the rows. In any dimension
   * in which the BitSet to be added contains a "1", the effect will be that 1's are changed to 0's until a
   * new 1 is added (e.g. the column '110' would become '001' and so forth)
   * 
   * The first method deals with floating point issues, and accelerates superposition by decomposing
   * the task into segments
   * 
   * @param incomingBitSet
   * @param weight
   */
  public void superpose(OpenBitSet incomingBitSet, double weight) {
    // If fractional weights are used, encode all weights as integers (1000 x double value).
    weight = (int) Math.round(weight * Math.pow(10, decimalPlaces));
    if (weight == 0) return;

    // Keep track of number (or cumulative weight) of votes.
    actualVotes += weight;

    // Decompose superposition task such that addition of some power of 2 (e.g. 64) is accomplished
    // by beginning the process at the relevant row (e.g. 7) instead of starting multiple (e.g. 64)
    // superposition processes at the first row.
    int logfloor = (int) (Math.floor(Math.log(weight)/Math.log(2)));

    if (logfloor < votingRecord.size() - 1) {
      while (logfloor > 0) {
        superpose(incomingBitSet, logfloor);	
        weight = weight - (int) Math.pow(2,logfloor);
        logfloor = (int) (Math.floor(Math.log(weight)/Math.log(2)));	
      }
    }

    // Add remaining component of weight incrementally.
    for (int x =0; x < weight; x++)
      superpose(incomingBitSet, 0);
  }

  /**
   * Performs superposition by sweeping a bitset across the voting record such that
   * for any column in which the incoming bitset contains a '1', 1's are changed
   * to 0's until a new 1 can be added, facilitating incrementation of the
   * binary number represented in this column.
   * 
   * @param incomingBitSet the bitset to be added
   * @param rowfloor the of the voting record to start the sweep at
   */
  private void superpose(OpenBitSet incomingBitSet, int rowfloor) {
    // Attempt to save space when minimum value across all columns > 0
    // by decrementing across the board and raising the minimum where possible.
    int max = getMaximum();	

    if (max > 0) {	
      decrement(max);
    }

    // Handle overflow: if any column that will be incremented
    // contains all 1's, add a new row to the voting record.
    tempSet.xor(tempSet);
    tempSet.xor(incomingBitSet);

    for (int x = rowfloor; x < votingRecord.size() && tempSet.cardinality() > 0; x++) {
      tempSet.and(votingRecord.get(x));
    }

    if (tempSet.cardinality() > 0) {
      votingRecord.add(new OpenBitSet(dimensions));
    }

    // Sweep copy of bitset to be added across rows of voting record.
    // If a new '1' is added, this position in the copy is changed to zero
    // and will not affect future rows.
    // The xor step will transform 1's to 0's or vice versa for 
    // dimensions in which the temporary bitset contains a '1'.
    votingRecord.get(rowfloor).xor(incomingBitSet);

    tempSet.xor(tempSet);
    tempSet.xor(incomingBitSet);

    for (int x = rowfloor + 1; x < votingRecord.size(); x++) {	
      tempSet.andNot(votingRecord.get(x-1)); //if 1 already added, eliminate dimension from tempSet
      votingRecord.get(x).xor(tempSet);	
      votingRecord.get(x).trimTrailingZeros(); //attempt to save in sparsely populated rows
    }
  }

  /**
   * Reverses a string - simplifies the decoding of the binary vector for the 'exact' method
   * although it wouldn't be difficult to reverse the counter instead
   */
  public static String reverse(String str) {
    if ((null == str) || (str.length() <= 1)) {
      return str;
    }
    return new StringBuffer(str).reverse().toString();
  }

  /**
   * Returns a bitset with a "1" in the position of every dimension
   * that exactly matches the target number.
   */
  private OpenBitSet exact(int target) {
    if (target == 0) {
      tempSet.set(0, dimensions);
      tempSet.xor(votingRecord.get(0));
      for (int x=1; x < votingRecord.size(); x++)
        tempSet.andNot(votingRecord.get(x));
      return tempSet;
    }
    String inbinary = reverse(Integer.toBinaryString(target));

    tempSet.xor(tempSet);
    tempSet.xor(votingRecord.get(inbinary.indexOf("1")));

    for (int q =0; q < votingRecord.size(); q++) {
      if (q < inbinary.length())
        if (inbinary.charAt(q) == '1')
          tempSet.and(votingRecord.get(q));	
        else 
          tempSet.andNot(votingRecord.get(q));	
    }
    // TODO(widdows): Figure out if there's a good reason for returning a member variable.
    // Seems redundant to do this.
    return tempSet;		
  }

  /**
   * This method is used determine which dimensions will receive 1 and which 0 when the voting
   * process is concluded. It produces an OpenBitSet in which
   * "1" is assigned to all dimensions with a count > 50% of the total number of votes (i.e. more 1's than 0's added)
   * "0" is assigned to all dimensions with a count < 50% of the total number of votes (i.e. more 0's than 1's added)
   * "0" or "1" are assigned to all dimensions with a count = 50% of the total number of votes (i.e. equal 1's and 0's added)
   * 
   * @return an OpenBitSet representing the superposition of all vectors added up to this point
   */
  protected OpenBitSet concludeVote() {
    if (votingRecord.size() == 0) return new OpenBitSet(dimensions);
    else
      return concludeVote(actualVotes);
  }

  protected OpenBitSet concludeVote(int target) {
    int target2 = (int) Math.ceil((double) target / (double) 2);
    target2 = target2 - minimum;

    // Unlikely other than in testing: minimum more than half the votes
    if (target2 < 0) {
      OpenBitSet ans = new OpenBitSet(dimensions);
      ans.set(0, dimensions-1);
      return ans;
    }

    boolean even = (target % 2 == 0);
    OpenBitSet result = concludeVote(target2, votingRecord.size() - 1);

    if (even) {
      tempSet = exact(target2);
      boolean switcher = true;
      // 50% chance of being true with split vote.
      for (int q = 0; q < dimensions; q++) {
        if (tempSet.fastGet(q)) {
          switcher = !switcher;
          if (switcher) tempSet.fastClear(q);
        }
      }
      result.andNot(tempSet);
    }
    return result;
  }

  protected OpenBitSet concludeVote(int target, int row_ceiling) {
    logger.info("Entering conclude vote, target " + target + " row_ceiling " + row_ceiling
        + " vector\n" + toString());
    if (target == 0)
      return new OpenBitSet(dimensions);

    double rowfloor = Math.log(target)/Math.log(2);
    int row_floor = (int) Math.floor(rowfloor);  //for 0 index
    int remainder =  target - (int) Math.pow(2,row_floor);
    //System.out.println(target+"\t"+rowfloor+"\t"+row_floor+"\t"+remainder);

    if (row_ceiling == 0 && target == 1) {
      return votingRecord.get(0);
    }

    if (remainder == 0) {
      // Simple case - the number we're looking for is 2^n, so anything with a "1" in row n or above is true.
      OpenBitSet definitePositives = new OpenBitSet(dimensions);
      for (int q = row_floor; q <= row_ceiling; q++)
        definitePositives.or(votingRecord.get(q));
      return definitePositives;
    }
    else {
      // Simple part of complex case: first get anything with a "1" in a row above n (all true).
      OpenBitSet definitePositives = new OpenBitSet(dimensions);
      for (int q = row_floor+1; q <= row_ceiling; q++)
        definitePositives.or(votingRecord.get(q));

      // Complex part of complex case: get those that have a "1" in the row of n.
      OpenBitSet possiblePositives = (OpenBitSet) votingRecord.get(row_floor).clone();
      OpenBitSet definitePositives2 = concludeVote(remainder, row_floor-1);

      possiblePositives.and(definitePositives2);
      definitePositives.or(possiblePositives);		
      return definitePositives;
    }
  }


  /**
   * Decrement every dimension. Assumes at least one count in each dimension
   * i.e: no underflow check currently - will wreak havoc with zero counts
   */
  public void decrement() {	
    tempSet.set(0, dimensions);
    for (int q = 0; q < votingRecord.size(); q++) {
      votingRecord.get(q).xor(tempSet);
      tempSet.and(votingRecord.get(q));
    }
  }

  /**
   * Decrement every dimension by the number passed as a parameter. Again at least one count in each dimension
   * i.e: no underflow check currently - will wreak havoc with zero counts
   */
  public void decrement(int weight) {
    if (weight == 0) return;
    minimum+= weight;

    int logfloor = (int) (Math.floor(Math.log(weight)/Math.log(2)));

    if (logfloor < votingRecord.size() - 1) {
      while (logfloor > 0) {
        selected_decrement(logfloor);	
        weight = weight - (int) Math.pow(2,logfloor);
        logfloor = (int) (Math.floor(Math.log(weight)/Math.log(2)));
      }
    }

    for (int x = 0; x < weight; x++) {
      decrement();
    }
  }

  public void selected_decrement(int floor) {
    tempSet.set(0, dimensions);
    for (int q = floor; q < votingRecord.size(); q++) {
      votingRecord.get(q).xor(tempSet);
      tempSet.and(votingRecord.get(q));
    }
  }

  /**
   * Returns the highest value shared by all dimensions.
   */
  public int getMaximum() {
    int thismaximum = 0;
    tempSet.xor(tempSet);
    for (int x = votingRecord.size()-1; x >= 0; x--) {
      tempSet.or(votingRecord.get(x));
      if (tempSet.cardinality() == dimensions) {
        thismaximum += (int) Math.pow(2, x);
        tempSet.xor(tempSet);
      }
    }
    return thismaximum;	
  }

  @Override
  /**
   * Normalizes the vector, converting sparse to dense representations in the process.
   */
  public void normalize() {
    if (isSparse) elementalToSemantic();
    this.bitSet = concludeVote();
  }

  @Override
  /**
   * Writes vector out to object output stream.  Converts to dense format if necessary.
   */
  public void writeToLuceneStream(IndexOutput outputStream) {
    if (isSparse) {
      elementalToSemantic();
    }
    long[] bitArray = bitSet.getBits();
    for (int i = 0; i < bitArray.length; ++i) {
      try {
        outputStream.writeLong(bitArray[i]);
      } catch (IOException e) {
        logger.severe("Couldn't write binary vector to lucene output stream.");
        e.printStackTrace();
      }
    }
  }

  @Override
  /**
   * Reads a (dense) version of a vector from a Lucene input stream. 
   */
  public void readFromLuceneStream(IndexInput inputStream) {
    long bitArray[] = new long[(dimensions / 64) + 1];
    for (int i = 0; i <= dimensions / 64; ++i) {
      try {
        bitArray[i] = inputStream.readLong();
      } catch (IOException e) {
        logger.severe("Couldn't read binary vector from lucene output stream.");
        e.printStackTrace();
      }
    }
    this.bitSet = new OpenBitSet(bitArray, bitArray.length);
    this.isSparse = false;
  }

  @Override
  /**
   * Writes vector to a string of the form 010 etc. (no delimiters). 
   * 
   * No terminating newline or delimiter.
   */
  public String writeToString() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < dimensions; ++i) {
      builder.append(Integer.toString(bitSet.getBit(i)));
    }
    return builder.toString();
  }

  @Override
  /**
   * Writes vector from a string of the form 01001 etc.
   */
  public void readFromString(String input) {
    if (input.length() != dimensions) {
      throw new IllegalArgumentException("Found " + (input.length()) + " possible coordinates: "
          + "expected " + dimensions);
    }

    for (int i = 0; i < dimensions; ++i) {
      if (input.charAt(i) == '1')
        bitSet.fastSet(i);
    }
  }

  /**
   * Automatically translate elemental vector (no storage capacity) into 
   * semantic vector (storage capacity initialized, this will occupy RAM)
   */
  protected void elementalToSemantic() {
    if (!isSparse) {
      logger.warning("Tryied to transform an elemental vector which is not in fact elemental."
          + "This may be a programming error.");
      return;
    }

    this.votingRecord = new ArrayList<OpenBitSet>();
    this.tempSet = new OpenBitSet(dimensions);
    this.isSparse = false;

  }

  // Available for testing and copying.
  protected BinaryVector(OpenBitSet inSet) {
    this.dimensions = (int) inSet.size();
    this.bitSet = inSet;
  }

  // Monitor growth of voting record.
  protected int numRows() {
    return votingRecord.size();
  }
}

