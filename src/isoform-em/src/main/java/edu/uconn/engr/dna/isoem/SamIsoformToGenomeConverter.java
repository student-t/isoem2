package edu.uconn.engr.dna.isoem;

import edu.uconn.engr.dna.format.CoordinatesMapper;
import edu.uconn.engr.dna.format.Isoform;
import edu.uconn.engr.dna.format.Isoforms;
import edu.uconn.engr.dna.util.Converter;
import edu.uconn.engr.dna.util.IntervalVisitor;
import edu.uconn.engr.dna.util.Intervals;
import edu.uconn.engr.dna.util.TokenizerWithReplace;
import java.util.ArrayList;
import java.util.List;
//import edu.uconn.engr.dna.util.Utils;

public class SamIsoformToGenomeConverter implements Converter<String, String> {
	private static final int READ_PAIRED_FLAG = 0x1;
	private static final int SEQUENCE_UNMAPPED = 0x4;
	private static final int MATE_UNMAPPED = 0x8;
	private static final int READ_STRAND_FLAG = 0x10;
	private static final int MATE_STRAND_FLAG = 0x20;

	private Isoforms isoforms;
	private TokenizerWithReplace rTok;
	private VisitorForGenomeCigar visitorForGenomeCigar;
        private int polyAtail;
        private boolean ignorePairs;

//sahar big fix for pairs of unequal length; pairing ignored because mate length is needed (and not available) to convert coordiates
        public SamIsoformToGenomeConverter(Isoforms isoforms, boolean ignorePairs) {
                this.polyAtail = 0;
		this.isoforms = isoforms;
		char separator = '\t';
                this.ignorePairs = ignorePairs;
		this.rTok = new TokenizerWithReplace(separator);
		visitorForGenomeCigar = new VisitorForGenomeCigar();
	}

//sahar big fix for pairs of unequal length; pairing ignored because mate length is needed (and not available) to convert coordiates
	public SamIsoformToGenomeConverter(Isoforms isoforms, int polyAtail, boolean ignorePairs) {
                this.polyAtail = polyAtail;
		this.isoforms = isoforms;
		char separator = '\t';
                this.ignorePairs = ignorePairs;
		this.rTok = new TokenizerWithReplace(separator);
		visitorForGenomeCigar = new VisitorForGenomeCigar();
	}
	public String convert(String line) {
		// Parse line
		rTok.setLine(line);
		rTok.skipNextWithCopy(); // skip readName
		int flags = rTok.nextInt();
		if ((flags & SEQUENCE_UNMAPPED) != 0) { //  read is not mapped
			return null;
		}
		String referenceSequenceName = rTok.nextString(); // (isoform)

		Isoform isoform = isoforms.getValue(referenceSequenceName);
		if (isoform == null) {
			return null;
		}
		int newFlags = getNewFlags(flags, isoform);
//bug fix
// postpone updating these fields because flags might change after reading pair information
//		rTok.update(newFlags); // add updated flags
//		rTok.update(isoform.getChromosome()); // add chromosome

		int alignmentStart = rTok.nextInt();
		rTok.skipNext(); // skip <MAPQ>
		int mapqs = rTok.getLastTokenStart(); //but keep track of location
		int mapqe = rTok.getLastTokenEnd();
//indel handling
                String transcriptCigar=rTok.nextString();
		//rTok.skipNext();
		//int readLength = getReadLength(line, rTok.getLastTokenStart(), rTok.getLastTokenEnd());
              int readLength = getReadLength(transcriptCigar);

		alignmentStart = getReadIsoformPositionInPlusStrand(alignmentStart, isoform, readLength);
//debug
//		System.out.println("Before getGenomePosition for read");
		int genomeAlignmentStart = getGenomePosition(alignmentStart, isoform);
                int clippedReadLength = getUnclippedReadLength(transcriptCigar);
// bug fix for clipping the bases mapped to the polyA tail; previously readLength which is the number of bases
// on the transcript that the read maps to was passed (includes D in the CIGAR and does not include I)
// This was changed to clippedReadLength; the number of bases in the read that maps to the transcript;
// includes I and not D. Also does not include S since alignment start gives the actualy start where the unclipped
// part of the read maps
                int BasesInPolyA=CoordinatesMapper.numberOfBasesMappedToPolyAtail(isoform.getExons(),alignmentStart, clippedReadLength, polyAtail, isoform.getStrand());


                if (BasesInPolyA == readLength)
                    return null;
//bug fix
// postpone updating these fields because flags might change after reading pair information
//		rTok.update(genomeAlignmentStart); // add updated alignment start
//		rTok.update(line, mapqs, mapqe); // add MAPQ unchanged
//indel handling

                CharSequence genomeCigar = getGenomeCigar(readLength, alignmentStart, isoform,transcriptCigar,BasesInPolyA);
//sahar bug fix when mate is mapped to a different isoform;
// temporary solution: pair information will be removed becasue mate length is needed ( and not available)
// to calculate its genomic coordinates
//		rTok.update(genomeCigar); // postpone updating these fields because flags might change after reading pair information
//		rTok.skipNextWithCopy(); // skip <MRNM>
		String mateReferenceSequenceName = rTok.nextString(); // (mate isoform)
		int matePosition = rTok.nextInt();
                int iSize = rTok.nextInt();
                iSize = 0; //was previously left as is (not accounting for introns) and it is not used; just set it to 0
                if (!ignorePairs &&( mateReferenceSequenceName.equals("=") || mateReferenceSequenceName.equals(referenceSequenceName)) && matePosition != 0) {
                            matePosition = getReadIsoformPositionInPlusStrand(matePosition, isoform, readLength);
//debug
//		System.out.println("Before getGenomePosition for mate");
                            matePosition = getGenomePosition(matePosition, isoform);
                }
                else {
                    mateReferenceSequenceName = new String("*");
                    matePosition = 0;
                    newFlags = newFlags & 0xFFD6; // 1111 1111 1101 0110; turing off 0x01, 0x08, 0x20; bits related to pairs
                }

		rTok.update(newFlags); // add updated flags
                rTok.update(isoform.getChromosome()); // add chromosome
		rTok.update(genomeAlignmentStart); // add updated alignment start
		rTok.update(line, mapqs, mapqe); // add MAPQ unchanged
		rTok.update(genomeCigar);
                rTok.update(mateReferenceSequenceName);
                rTok.update(matePosition);
//                rTok.skipNextWithCopy(); // skip <ISIZE>
                rTok.update(iSize);

		if (isoform.getStrand() == '-') {
			rTok.replaceNextTokenWithReverseComplement(); // sequence
			rTok.replaceNextTokenWithReverse(); // quality //should add one for cigar
			return rTok.getNewLine("XS:A:-");
		} else {
			return rTok.getNewLine("XS:A:+");
		}
	}

	public static int getReadIsoformPositionInPlusStrand(int alignmentStart, Isoform isoform, int readLength) {
		return getReadIsoformPositionInPlusStrand(alignmentStart, isoform.getStrand(),
				isoform.length(), readLength);
	}

	public static int getReadIsoformPositionInPlusStrand(int alignmentStart, char strand,
			int isoformLength, int readLength) {
		
//debug
//		System.out.println("isoformLength readKebgth "+isoformLength+" "+readLength);
//		System.out.println("isoformLength - alignmentStart - readLength + 2 = "+isoformLength+" - "+alignmentStart+" - "+readLength+" + 2");
//		System.out.println("isoformLength - alignmentStart - readLength + 2 = "+isoformLength+" - "+alignmentStart+" - "+readLength+" + 2");
		if (strand == '-') {
			return isoformLength - alignmentStart - readLength + 2;
		}
		return alignmentStart;
		
	}

	private int getGenomePosition(int alignmentStart, Isoform isoform) {
		return (int) CoordinatesMapper.isoformToGenomeCoordinate(isoform.getExons(), alignmentStart, polyAtail, isoform.getStrand());
	}

	private int getNewFlags(int flags, Isoform isoform) {
		if (isoform.getStrand() == '-') {
			boolean readPaired = ((flags & READ_PAIRED_FLAG) != 0)
					&& ((flags & SEQUENCE_UNMAPPED) == 0)
					&& ((flags & MATE_UNMAPPED) == 0);
			flags ^= READ_STRAND_FLAG; // flip read strand
			if (readPaired) {
				flags ^= MATE_STRAND_FLAG; // flip mate strand
			}
		}
		return flags;
	}

        private CharSequence buildCigar(List<Integer> cigarElemsLen, List<Character> cigarElems, char strand, int basesInPolyAtail) {
		final StringBuilder cigar = new StringBuilder();
                int startAt = cigarElems.size()-1;
                int endAt = 0;
                int saveBasesInPolyA = basesInPolyAtail;

//                System.out.println("In buildCigar call BasesInPolyA");
//		System.out.println(basesInPolyAtail);

                //System.err.println("cigarElems.size() "+cigarElems.size());
                // polyA tail clipping do it while processing the last element of the CIGAR if
                // transcript is on +ve strand. Case of -ve strand will be done in the iteration i = 0
                // in the loop below (first element in the CIGAR.
                if (strand == '+') {
                    if (cigarElems.get(startAt) == 'S') {
//                        System.err.println("if (cigarElems.get(startAt) == 'S'");
//                        System.err.println("startAt "+startAt+" cigarElemsLen.get(startAt) "+cigarElemsLen.get(startAt));

//                        System.err.println(cigarElemsLen.get(startAt)+basesInPolyAtail+"S");
                           cigar.append((int) cigarElemsLen.get(startAt) + basesInPolyAtail);
                           cigar.append('S');
                           startAt--;
                    }
                    else if (basesInPolyAtail > 0) {
//                        System.err.println("else if (basesInPolyAtail > 0)");
//                        System.err.println(basesInPolyAtail+"S");
                           cigar.append(basesInPolyAtail);
                           cigar.append('S');
                    }
                }
               else {// -ve strand isoform
                    if (cigarElems.get(endAt) == 'S') {
//                        System.err.println("if (cigarElems.get(startAt) == 'S'");
//                        System.err.println("startAt "+startAt+" cigarElemsLen.get(startAt) "+cigarElemsLen.get(startAt));

//                        System.err.println(cigarElemsLen.get(0)+basesInPolyAtail+"S");
                           cigar.append((int) cigarElemsLen.get(0) + basesInPolyAtail);
                           cigar.append('S');
                           endAt++;
                    }
                    else if (basesInPolyAtail > 0) {
//                        System.err.println("else if (basesInPolyAtail > 0)");
//                        System.err.println(basesInPolyAtail+"S");
                           cigar.append(basesInPolyAtail);
                           cigar.append('S');
                    }
                }
                if (cigarElems.get(startAt) == 'N')
                    startAt--;
                for (int i = startAt; i >= endAt;  i--) {
//                   if (i > 0)  //bug fix for converting to pileup. samtools do not like CIGARs such as  3S34M4831N2I13M1S with I after N
//                       if (cigarElems.get(i) == 'I' && cigarElems.get(i-1) == 'N')
//                           return null;

                // polyA tail clipping do it while processing the first element of the CIGAR if
                // transcript is on -ve strand. Case of first element is not S will be done
                // at the end of loop below (after adding first element of CIGAR)

//                    if (i == startA&& strand == '-' && cigarElems.get(i) == 'S') {
//                    if (i == 0 && strand == '-' && cigarElems.get(i) == 'S') {
//                        System.err.println("-ve i = 0 and element = 0 .. inserting at 0");
//                        System.err.println(cigarElemsLen.get(0)+basesInPolyAtail+"S");
//                           cigar.insert(0,'S');
//                           cigar.insert(0,(int) cigarElemsLen.get(0) + basesInPolyAtail);
//fix for SNVQ; many SNVs detected from reads mapping to -ve strand transcripts
//                           cigar.append((int) cigarElemsLen.get(0) + basesInPolyAtail);
 //                          cigar.append('S');
//                           startAt--;
//                           break;
//                    }
//                   System.err.println("inside the loop ..  i = "+i+" decrementing basesInPolyAtail and elemLength");
//                   System.err.println("before");
//                   System.err.println("basesInPolyAtail "+basesInPolyAtail);
//                   System.err.println("cigarElemsLen.get(i) "+cigarElemsLen.get(i));
                   int elemLen = Math.max(0,cigarElemsLen.get(i) - basesInPolyAtail);
                   basesInPolyAtail = Math.max(0,basesInPolyAtail-cigarElemsLen.get(i));
//                   System.err.println("after");
//                   System.err.println("basesInPolyAtail "+basesInPolyAtail);
//                   System.err.println("elemLen "+elemLen);
		     if (elemLen > 0) {
//fix for SNVQ; many SNVs detected from reads mapping to -ve strand transcripts
//	                if (strand == '+') {
                            cigar.insert(0,cigarElems.get(i));
                            cigar.insert(0,(int) elemLen);
//                        System.err.println("inside the loop ..i = "+i+"  inserting at 0");
//                        System.err.println(cigarElemsLen.get(0)+basesInPolyAtail+cigarElems.get(i));
//fix for SNVQ; many SNVs detected from reads mapping to -ve strand transcripts
// special handling of -ve strand transcripts was removed. It seemed incorrect when looking at mismatch stats
// need to go back and check that
// case under consideration now is a read clipped at both ends; since the read is reversed the clipped part
// should also be reversed (and the rest of the cigar as well)
// Now handeled in CharSequence getGenomeCigarforNegativeStrandTranscript
 //                  	}
//			else {
//		            cigar.append((int) elemLen);
  //          	            cigar.append(cigarElems.get(i));
//			}
		    }
                    else if (i > 0) //elemLen = 0  //if the whole current element cigar will be clipped it was preceded by 'N'; skip the 'N'
                       if (cigarElems.get(i-1) == 'N')
                           i--; 
//                     System.err.println("i,  cigarElems.get(i)  saveBasesInPolyA "+i+" "+cigarElems.get(i)+" "+saveBasesInPolyA);
//                    if (i == startAt && strand == '-' && cigarElems.get(i) != 'S' && saveBasesInPolyA > 0) {
//                        System.err.println("inside the loop ..i = 0  inserting at 0 saveBasesInPolyA");
//                        System.err.println(saveBasesInPolyA+"S");
//                           cigar.insert(0,'S');
//                           cigar.insert(0,saveBasesInPolyA);
//                    }
                }

                return cigar;

        }

        

	private CharSequence getGenomeCigar(int readLength, int alignmentStart, Isoform isoform, String transcriptomeCigar, int BasesInPolyA) {
//method updated to account for indels in cigar
		final StringBuilder cigar = new StringBuilder();
                visitorForGenomeCigar.setCigar(cigar);
                visitorForGenomeCigar.setTranscriptomeCigar(transcriptomeCigar);
		List<Integer> cigarElemsLen = new ArrayList<Integer>();
		List<Character> cigarElems = new ArrayList<Character>();

//		System.out.println("top of getGenomeCigar BasesInPolyA");
//                System.out.println(isoform.toString());
//                System.out.println(isoform.getChromosome());
//		System.out.println("alignmentStart "+alignmentStart+" strand "+isoform.getStrand());
//               System.out.println("isoform "+isoform.getExons().toString());


//		CoordinatesMapper.visitGenomeIntervals(isoform.getExons(),
//				alignmentStart, alignmentStart + readLength - 1,
//				visitorForGenomeCigar);
                Intervals exons = isoform.getExons();
                int cigarLength =  transcriptomeCigar.length();
                int currentExon;
                int cigarIndex = 0;
                int accumCigarToTramscriptLength = 0;

                 int currentExonLength; 
                int accumExonLength = 0; 

               if (isoform.getStrand() == '-')
                   return getGenomeCigarforNegativeStrandTranscript(readLength,alignmentStart,isoform,transcriptomeCigar,BasesInPolyA);
//               if (isoform.getStrand() == '-' && polyAtail > 0) {
//                    currentExon = 1; //skip polyAtail Exon
//                    accumExonLength = polyAtail; //(or lengnth of exon 1)
//                }
//                else
                    currentExon = 0;

                currentExonLength = exons.length(currentExon);
                accumExonLength += currentExonLength;
//                System.out.println("current Exon "+exons.getStart(currentExon)+" "+exons.getEnd(currentExon));

		  while (accumExonLength < alignmentStart) {
                   currentExon++;
                   currentExonLength = exons.length(currentExon);
                   accumExonLength += currentExonLength;
 //               System.err.println("current Exon "+exons.getStart(currentExon)+" "+exons.getEnd(currentExon));

		
		  }
 //                System.err.println("accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);
		  accumExonLength -= (alignmentStart -1);
//                 System.err.println("after subtracting alignment start accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);
                while (cigarIndex < cigarLength) {
                    StringBuilder cigarElemLength = new StringBuilder();
                     while ((cigarIndex < cigarLength) && (Character.isDigit(transcriptomeCigar.charAt(cigarIndex)))){
                            cigarElemLength.append(transcriptomeCigar.charAt(cigarIndex));
                            cigarIndex++;
                    }
                    //int elemLen = Utils.parseInt(cigarElemLength,10,0,cigarElemLength.length()-1);
			int elemLen = Integer.parseInt(cigarElemLength.toString(),10);//,0,cigarElemLength.length()-1);

                    if (cigarIndex == cigarLength)
                        throw new IllegalArgumentException("Incorrect cigar string format" + transcriptomeCigar);
                    char cigarElement = transcriptomeCigar.charAt(cigarIndex);
                    cigarIndex++;
                    while (elemLen > 0) {
                        switch (cigarElement) {
                            case 'M':
                            case '=':
                            case 'X':
                            case 'D':
                                if ((accumCigarToTramscriptLength + elemLen) > (accumExonLength)) {
                                    if (currentExon == exons.length()-1)  //last exon
                                        throw new IllegalArgumentException("cigar string extends beyond transcript" + transcriptomeCigar);
                                    int nextElemLen = accumCigarToTramscriptLength + elemLen - accumExonLength;
					 int tempElemLen = elemLen - nextElemLen;
                                    cigarElemsLen.add(tempElemLen);
                                    cigarElems.add(cigarElement);
                                    accumCigarToTramscriptLength += tempElemLen;
                                    elemLen = nextElemLen;
                                }
                                else {
                                    cigarElemsLen.add(elemLen);
                                    cigarElems.add(cigarElement);
                                    accumCigarToTramscriptLength += elemLen;
                                    elemLen = 0;
                                }
                                break;
                            case 'S':
                                    cigarElemsLen.add(elemLen);
                                    cigarElems.add(cigarElement);
                                    elemLen = 0;
				    break;
                            case 'I':
                                cigarElemsLen.add(elemLen);
                                cigarElems.add(cigarElement);
//sahar
//these two lines never caused a detectable problem but they don't make sense. I's are bases in the read that are not in the reference
                                // accumCigarToTramscriptLength += elemLen;
        			// accumExonLength += elemLen; //although not a part of an exon insertion length has to be accounted for on the transcript
                                elemLen = 0;
                                break;
                        }
//                        System.err.println("cigarElement "+cigarElement+" elemLen "+elemLen);
//                        System.err.println("accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);
			   boolean moreInCigar = false; //not needed if elemLem > 0	
			   boolean nextElemI = false;		
			   if (elemLen == 0) 
				if (cigarIndex < cigarLength) {
					moreInCigar = true;
					int i = cigarIndex;
		                     while ((i < cigarLength) && (Character.isDigit(transcriptomeCigar.charAt(i))))
                      	              	i++;
                                     if ((transcriptomeCigar.charAt(i)== 'H') || (transcriptomeCigar.charAt(i)== 'S' ))
						moreInCigar = false;
				     if (transcriptomeCigar.charAt(i) == 'I')
					nextElemI = true;
				}
                        if ((accumCigarToTramscriptLength == accumExonLength) && ((elemLen > 0) || (moreInCigar && !nextElemI))) {
                            if (currentExon == exons.size()-1 && !nextElemI )  //last exon
                                    throw new IllegalArgumentException("cigar string extends beyond transcript" + transcriptomeCigar);
                        cigarElemsLen.add((int) (exons.getStart(currentExon + 1) - exons.getEnd(currentExon) - 1));
                        cigarElems.add('N');
                        currentExon++;
                        currentExonLength = exons.length(currentExon);
                        accumExonLength += currentExonLength;
  //                      System.err.println("after Adding N");
  //                      System.err.println("cigarElement "+cigarElement+" elemLen "+elemLen);
  //                      System.err.println("current Exon "+exons.getStart(currentExon)+" "+exons.getEnd(currentExon));
  //                      System.err.println("accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);

                        }
//                        System.err.println("moreInCigar "+moreInCigar+" elemLen "+elemLen);

                    }
                }
//		System.out.println("before buildCigar call BasesInPolyA");
//		System.out.println(BasesInPolyA);

                return buildCigar(cigarElemsLen, cigarElems,isoform.getStrand(),BasesInPolyA);
	}

        private CharSequence buildCigarforNegativeStrandTranscript(List<Integer> cigarElemsLen, List<Character> cigarElems, char strand, int basesInPolyAtail) {
		final StringBuilder cigar = new StringBuilder();
                int startAt = 0;
                int saveBasesInPolyA = basesInPolyAtail;

//                System.out.println("In buildCigar call BasesInPolyA");
//		System.out.println(basesInPolyAtail);

                //System.err.println("cigarElems.size() "+cigarElems.size());
                // polyA tail clipping do it while processing the last element of the CIGAR if
                // transcript is on +ve strand. Case of -ve strand will be done in the iteration i = 0
                // in the loop below (first element in the CIGAR.
                    if (cigarElems.get(0) == 'S') {
//                        System.err.println("if (cigarElems.get(startAt) == 'S'");
//                        System.err.println("startAt "+startAt+" cigarElemsLen.get(startAt) "+cigarElemsLen.get(startAt));

//                        System.err.println(cigarElemsLen.get(0)+basesInPolyAtail+"S");
                           cigar.append((int) cigarElemsLen.get(0) + basesInPolyAtail);
                           cigar.append('S');
                           startAt++;
                    }
                    else if (basesInPolyAtail > 0) {
//                        System.err.println("else if (basesInPolyAtail > 0)");
//                        System.err.println(basesInPolyAtail+"S");
                           cigar.append(basesInPolyAtail);
                           cigar.append('S');
                    }
                if (cigarElems.get(startAt) == 'N')
                    startAt--;
                for (int i = startAt; i <= cigarElems.size()-1;  i++) {
//                   if (i > 0)  //bug fix for converting to pileup. samtools do not like CIGARs such as  3S34M4831N2I13M1S with I after N
//                       if (cigarElems.get(i) == 'I' && cigarElems.get(i-1) == 'N')
//                           return null;

                // polyA tail clipping do it while processing the first element of the CIGAR if
                // transcript is on -ve strand. Case of first element is not S will be done
                // at the end of loop below (after adding first element of CIGAR)

//                    if (i == startAt && strand == '-' && cigarElems.get(i) == 'S') {
//                    if (i == 0 && strand == '-' && cigarElems.get(i) == 'S') {
//                        System.err.println("-ve i = 0 and element = 0 .. inserting at 0");
//                        System.err.println(cigarElemsLen.get(0)+basesInPolyAtail+"S");
//                           cigar.insert(0,'S');
//                           cigar.insert(0,(int) cigarElemsLen.get(0) + basesInPolyAtail);
//fix for SNVQ; many SNVs detected from reads mapping to -ve strand transcripts
//                           cigar.append((int) cigarElemsLen.get(0) + basesInPolyAtail);
 //                          cigar.append('S');
//                           startAt--;
//                           break;
//                    }
//                   System.err.println("inside the loop ..  i = "+i+" decrementing basesInPolyAtail and elemLength");
//                   System.err.println("before");
//                   System.err.println("basesInPolyAtail "+basesInPolyAtail);
//                   System.err.println("cigarElemsLen.get(i) "+cigarElemsLen.get(i));
                   int elemLen = Math.max(0,cigarElemsLen.get(i) - basesInPolyAtail);
                   basesInPolyAtail = Math.max(0,basesInPolyAtail-cigarElemsLen.get(i));
//                   System.err.println("after");
//                   System.err.println("basesInPolyAtail "+basesInPolyAtail);
//                   System.err.println("elemLen "+elemLen);
		     if (elemLen > 0) {
//fix for SNVQ; many SNVs detected from reads mapping to -ve strand transcripts
//	                if (strand == '+') {
                            cigar.append((int) elemLen);
                            cigar.append(cigarElems.get(i));
//                        System.err.println("inside the loop ..i = "+i+"  inserting at 0");
//                        System.err.println(cigarElemsLen.get(0)+basesInPolyAtail+cigarElems.get(i));
//fix for SNVQ; many SNVs detected from reads mapping to -ve strand transcripts
// special handling of -ve strand transcripts was removed. It seemed incorrect when looking at mismatch stats
// need to go back and check that
// case under consideration now is a read clipped at both ends; since the read is reversed the clipped part
// should also be reversed (and the rest of the cigar as well)
// Now handeled in CharSequence getGenomeCigarforNegativeStrandTranscript
 //                  	}
//			else {
//		            cigar.append((int) elemLen);
  //          	            cigar.append(cigarElems.get(i));
//			}
		    }
                    else if (i > 0) //elemLen = 0  //if the whole current element cigar will be clipped it was preceded by 'N'; skip the 'N'
                       if (cigarElems.get(i-1) == 'N')
                           i--;
//                     System.err.println("i,  cigarElems.get(i)  saveBasesInPolyA "+i+" "+cigarElems.get(i)+" "+saveBasesInPolyA);
//                    if (i == startAt && strand == '-' && cigarElems.get(i) != 'S' && saveBasesInPolyA > 0) {
//                        System.err.println("inside the loop ..i = 0  inserting at 0 saveBasesInPolyA");
//                        System.err.println(saveBasesInPolyA+"S");
//                           cigar.insert(0,'S');
//                           cigar.insert(0,saveBasesInPolyA);
//                    }
                }

                return cigar;

        }

	private CharSequence getGenomeCigarforNegativeStrandTranscript(int readLength, int alignmentStart, Isoform isoform, String transcriptomeCigar, int BasesInPolyA) {
//method updated to account for indels in cigar
		final StringBuilder cigar = new StringBuilder();
                visitorForGenomeCigar.setCigar(cigar);
                visitorForGenomeCigar.setTranscriptomeCigar(transcriptomeCigar);
		List<Integer> cigarElemsLen = new ArrayList<Integer>();
		List<Character> cigarElems = new ArrayList<Character>();

//		System.out.println("top of getGenomeCigar BasesInPolyA");
//                System.out.println(isoform.toString());
//                System.out.println(isoform.getChromosome());
//		System.out.println("alignmentStart "+alignmentStart+" strand "+isoform.getStrand());
//               System.out.println("isoform "+isoform.getExons().toString());


//		CoordinatesMapper.visitGenomeIntervals(isoform.getExons(),
//				alignmentStart, alignmentStart + readLength - 1,
//				visitorForGenomeCigar);
                Intervals exons = isoform.getExons();
                int cigarLength =  transcriptomeCigar.length();
                int currentExon;
                int cigarIndex = transcriptomeCigar.length() - 1;
                int accumCigarToTramscriptLength = 0;

                int currentExonLength;
                int accumExonLength = polyAtail;
//sahar bug fix for cases where no polyAtail is used
		  if (polyAtail != 0)
	                currentExon = 1; //skip polyAtail Exon
                  else
                     currentExon = 0;

                currentExonLength = exons.length(currentExon);
                accumExonLength += currentExonLength;
//                System.out.println("current Exon "+exons.getStart(currentExon)+" "+exons.getEnd(currentExon));

		  while (accumExonLength < alignmentStart) {
                   currentExon++;
                   currentExonLength = exons.length(currentExon);
                   accumExonLength += currentExonLength;
 //               System.err.println("current Exon "+exons.getStart(currentExon)+" "+exons.getEnd(currentExon));


		  }
//                 System.err.println("accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);
		  accumExonLength -= (alignmentStart -1);
//                 System.err.println("after subtracting alignment start accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);
                while (cigarIndex >= 0) {
                    StringBuilder cigarElemLength = new StringBuilder();
                    char cigarElement = transcriptomeCigar.charAt(cigarIndex);
                    cigarIndex--;
                    if (cigarIndex < 0 || !Character.isDigit(transcriptomeCigar.charAt(cigarIndex)))
                        throw new IllegalArgumentException("Incorrect cigar string format" + transcriptomeCigar);
                     while ((cigarIndex >= 0) && (Character.isDigit(transcriptomeCigar.charAt(cigarIndex)))){
                            cigarElemLength.insert(0,transcriptomeCigar.charAt(cigarIndex));
                            cigarIndex--;
                     }
		     int elemLen = Integer.parseInt(cigarElemLength.toString(),10);//,0,cigarElemLength.length()-1);

                    while (elemLen > 0) {
                        switch (cigarElement) {
                            case 'M':
                            case '=':
                            case 'X':
                            case 'D':
                                if ((accumCigarToTramscriptLength + elemLen) > (accumExonLength)) {
                                    if (currentExon == exons.length()-1)  //last exon
                                        throw new IllegalArgumentException("cigar string extends beyond transcript" + transcriptomeCigar);
                                    int nextElemLen = accumCigarToTramscriptLength + elemLen - accumExonLength;
					 int tempElemLen = elemLen - nextElemLen;
                                    cigarElemsLen.add(tempElemLen);
                                    cigarElems.add(cigarElement);
//debug
//                        System.err.println("cigarElement "+cigarElement+" elemLen "+tempElemLen);
                                    accumCigarToTramscriptLength += tempElemLen;
                                    elemLen = nextElemLen;
                                }
                                else {
                                    cigarElemsLen.add(elemLen);
                                    cigarElems.add(cigarElement);
//debug
//                        System.err.println("cigarElement "+cigarElement+" elemLen "+elemLen);
                                    accumCigarToTramscriptLength += elemLen;
                                    elemLen = 0;
                                }
                                break;
                            case 'S':
                                    cigarElemsLen.add(elemLen);
                                    cigarElems.add(cigarElement);
//debug
//                        System.err.println("cigarElement "+cigarElement+" elemLen "+elemLen);
                                    elemLen = 0;
				    break;
                            case 'I':
                                cigarElemsLen.add(elemLen);
                                cigarElems.add(cigarElement);
//sahar
//these two lines never caused a detectable problem but they don't make sense. I's are bases in the read that are not in the reference
//                                accumCigarToTramscriptLength += elemLen;
//        			 accumExonLength += elemLen; //although not a part of an exon insertion length has to be accounted for on the transcript
//debug
//                        System.err.println("cigarElement "+cigarElement+" elemLen "+elemLen);
                                elemLen = 0;
                                break;
                        }
//debug
//                        System.err.println("cigarElement "+cigarElement+" elemLen "+elemLen);
//                        System.err.println("accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);
			   boolean moreInCigar = false; //not needed if elemLem > 0
			   boolean nextElemI = false;
			   if (elemLen == 0)
				if (cigarIndex >= 0) {
					moreInCigar = true;
					int i = cigarIndex;
// parsing backwords
//		                     while ((i < cigarLength) && (Character.isDigit(transcriptomeCigar.charAt(i))))
//                    	              	i++;
                                     if ((transcriptomeCigar.charAt(i)== 'H') || (transcriptomeCigar.charAt(i)== 'S' ))
						moreInCigar = false;
				     if (transcriptomeCigar.charAt(i) == 'I')
					nextElemI = true;
				} // if cigarIndex >= 0
                        if ((accumCigarToTramscriptLength == accumExonLength) && ((elemLen > 0) || (moreInCigar && !nextElemI))) {
                            if (currentExon == exons.size()-1 && !nextElemI)  { //last exon
//debug
//                                  System.err.println("cigarIndex "+cigarIndex+" cigarIndex "+cigarIndex);
                                    throw new IllegalArgumentException("cigar string extends beyond transcript" + transcriptomeCigar);
			   }  // if last exon
//debug
//                        System.err.println("cigarIndex "+cigarIndex+"moreInCigar "+moreInCigar+"elemLen "+elemLen );
			   //if (transcriptomeCigar.charAt(cigarIndex) != 'I') {
	                        cigarElemsLen.add((int) (exons.getStart(currentExon + 1) - exons.getEnd(currentExon) - 1));
        	                cigarElems.add('N');
                	        currentExon++;
                        	currentExonLength = exons.length(currentExon);
	                        accumExonLength += currentExonLength;
			   //}  // if 
//debug
//                        System.err.println("after Adding N");
//                        System.err.println("cigarElement "+cigarElement+" elemLen "+elemLen);
//                        System.err.println("current Exon "+exons.getStart(currentExon)+" "+exons.getEnd(currentExon));
//                        System.err.println("accumCigarToTramscriptLength "+accumCigarToTramscriptLength+" accumExonLength "+accumExonLength+" currentExon "+currentExon);

                        }
//debug
//                        System.err.println("moreInCigar "+moreInCigar+" elemLen "+elemLen);

                    }
                }
//		System.out.println("before buildCigar call BasesInPolyA");
//		System.out.println(BasesInPolyA);

                return buildCigarforNegativeStrandTranscript(cigarElemsLen, cigarElems,isoform.getStrand(),BasesInPolyA);
	}

	//private int getReadLength(String cigar, int start, int end) {
        private int getReadLength(String cigar) {
//indels fix
// method updated to account for different cigar elements; however, read length is not currently used in creating the
// cigar for genome coordinate alignment as before
// it is only used to claculate the starting alignment position when the transcript strans is -ve.
// It should actually return the number of bases the read maps to on the transcript
// i.e number of matches; mismatches and deletions from the transcript

		int i = 0;//start;
		int n = cigar.length();//end;
                int elemLen;
                int readLen=0;
                int start = 0;


		while (i < n) {
			// StringBuilder cigarElemLength = new StringBuilder();
                    while ((i < n) && (Character.isDigit(cigar.charAt(i)))){
			//cigarElemLength.append(cigar.charAt(i));
                     i++;
                    }
			String temp=cigar.substring(start, i);
	              //elemLen=Utils.parseInt(cigar,10,start,i-1);
			//elemLen=Integer.parseInt(cigar.substring(start, i),10);
			elemLen=Integer.parseInt(temp,10);
                    if (i == n)
                        throw new IllegalArgumentException("Incorrect cigar string format" + cigar); //cigar.substring(start, end));
                    switch(cigar.charAt(i)){
                        case 'M':
                        case 'D':
                        case '=':
                        case 'X':
                            readLen+=elemLen;
	                     //elemLen+= Utils.parseInt(cigarElemLength.toString(),10,0,cigarElemLength.length()-1);
                            break;
                    }
                    i++;
                    start = i;

		}
		return readLen;
	}
        private int getUnclippedReadLength(String cigar) {
//Added for fixing a bug in polyA clipping; See details at method call
// returns the number of unclipped bases in a read
// i.e number of matches; mismatches and insertions
// result will be used in CoordinatesMapper.numberOfBasesMappedToPolyAtail()

		int i = 0;//start;
		int n = cigar.length();//end;
                int elemLen;
                int readLen=0;
                int start = 0;


		while (i < n) {
			// StringBuilder cigarElemLength = new StringBuilder();
                    while ((i < n) && (Character.isDigit(cigar.charAt(i)))){
			//cigarElemLength.append(cigar.charAt(i));
                     i++;
                    }
			String temp=cigar.substring(start, i);
	              //elemLen=Utils.parseInt(cigar,10,start,i-1);
			//elemLen=Integer.parseInt(cigar.substring(start, i),10);
			elemLen=Integer.parseInt(temp,10);
                    if (i == n)
                        throw new IllegalArgumentException("Incorrect cigar string format" + cigar); //cigar.substring(start, end));
                    switch(cigar.charAt(i)){
                        case 'M':
                        case 'I':
                        case '=':
                        case 'X':
                            readLen+=elemLen;
	                     //elemLen+= Utils.parseInt(cigarElemLength.toString(),10,0,cigarElemLength.length()-1);
                            break;
                    }
                    i++;
                    start = i;

		}
		return readLen;
	}

	static class VisitorForGenomeCigar implements IntervalVisitor {
		private int lastEnd;
		private StringBuilder cigar;
//indels
                private String transcriptomeCigar;

		@Override
// method currently not used. cigar is built in  getGenomeCigar
		public void visit(int isoformIntervalStart, int isoformIntervalEnd,
				int genomeIntervalStart, int genomeIntervalEnd) {
			if (!(cigar.length() == 0)) {
				if (genomeIntervalStart < lastEnd) {
					throw new IllegalStateException("Genome interval start " + genomeIntervalStart
							+ " received after genome interval end " + lastEnd);
				}
				cigar.append((int) (genomeIntervalStart - lastEnd - 1));
				cigar.append('N');
			}
			lastEnd = genomeIntervalEnd;
			cigar.append((int) (genomeIntervalEnd - genomeIntervalStart + 1));
			cigar.append('M');

		}
//method added for handling indels in cigar
                public void setTranscriptomeCigar(String transcriptomeCigar) {
                    this.transcriptomeCigar = transcriptomeCigar;
                }

//method added for handling indels in cigar
                public String getTranscriptomeCigar() {
                    return(transcriptomeCigar);
                }
		public void setCigar(StringBuilder cigar) {
			this.cigar = cigar;
			this.lastEnd = 0;
		}
	}
}
