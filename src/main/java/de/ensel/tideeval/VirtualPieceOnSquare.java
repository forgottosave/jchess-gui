/*
 *     TideEval - Wired New Chess Algorithm
 *     Copyright (C) 2023 Christian Ensel
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.ensel.tideeval;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

import static de.ensel.tideeval.ChessBasics.*;
import static de.ensel.tideeval.ChessBoard.*;
import static de.ensel.tideeval.ChessBasics.ANYWHERE;
import static de.ensel.tideeval.ConditionalDistance.INFINITE_DISTANCE;
import static java.lang.Math.*;

public abstract class VirtualPieceOnSquare implements Comparable<VirtualPieceOnSquare> {
    protected final ChessBoard board;
    protected final int myPceID;
    protected final int myPceType;

    protected final int myPos;

    private int relEval;  // is in board perspective like all evals! (not relative to the color, just relative as seen from the one piece)
    private int relClashContrib;  // tells if Piece is needed in Clash or other benefit. relEval can be 0, but still has a contribution. if Pieves moved away instead, it would miss this contribution.

    protected ConditionalDistance rawMinDistance;   // distance in hops from corresponding real piece.
                                                    // it does not take into account if this piece is in the way of another of the same color
    protected ConditionalDistance minDistance;  // == null if "dirty" (after change of rawMinDistance) other ==rawMinDistance oder +1/+n, if same color Piece is on square
    protected ConditionalDistance suggestionTo1HopNeighbour;  // what would be suggested to a "1-hop-neighbour",
                                                // this is also ==null if "dirty" and used for all types of pieces, even sliding
    /**
     * "timestamp" when the rawMinDistance of this vPce was changed the last "time" (see ChessBoard: boardmoves+fineTicks)
     */
    protected long latestChange;

    /**
     * chances (or risks) if myPiece was here already.
     * chances distinguishes between chances for different targets, so chances for same targets  are not summed up,
     * but the max is taken.
     */
    private EvalPerTargetAggregation chances;
    private EvalPerTargetAggregation moveAwayChances;  // similar to chances, but only used at myPos
    private EvalPerTargetAggregation futureChances;    // just a helper during the aggregation of chances to detect fork possibilities and same target situations
    private int forkingChance;                         // helper in the same phase collecting future chances

    private boolean isCheckGiving;
    private VirtualPieceOnSquare abzugChecker;

    private Set<VirtualPieceOnSquare> predecessors;
    private Set<VirtualPieceOnSquare> directAttackVPcs;
    private Set<VirtualPieceOnSquare> shortestReasonableUnconditionedPredecessors;
    private Set<VirtualPieceOnSquare> shortestReasonablePredecessors;
    private Set<Move> firstMovesWithReasonableShortestWayToHere;
    private int mobilityFromHere;    // a value, somehow summing mobilty up
    private int mobilityMapFromHere; // a 64-bitmap, one bit for each square

    private int priceToKill;
    private boolean killable;


    public VirtualPieceOnSquare(ChessBoard myChessBoard, int newPceID, int pceType, int myPos) {
        this.board = myChessBoard;
        this.myPceType = pceType;
        this.myPos = myPos;
        myPceID = newPceID;
        latestChange = 0;
        //valueInDir = new int[MAXMAINDIRS];
        resetDistances();
        //resetValues();
        relEval = NOT_EVALUATED;
        relClashContrib = NOT_EVALUATED;
        resetBasics();
    }

    public static VirtualPieceOnSquare generateNew(ChessBoard myChessBoard, int newPceID, int myPos) {
        int pceType = myChessBoard.getPiece(newPceID).getPieceType();
        if (isSlidingPieceType(pceType))
            return new VirtualSlidingPieceOnSquare(myChessBoard,newPceID, pceType, myPos);
        if (isPawn(pceType))
            return new VirtualPawnPieceOnSquare(myChessBoard,newPceID, pceType, myPos);
        return new VirtualOneHopPieceOnSquare(myChessBoard,newPceID, pceType, myPos);
    }

    int addBetterChance(int benefit, int futureLevel, int relEval, int relEvalFL) {
        if (relEvalFL < futureLevel) {
            if (relEval != 0)
                addChance(relEval, relEvalFL);
            if (isBetterThenFor(benefit, relEval, color()))
                addChance(benefit, futureLevel);
            else
                benefit = relEval;
        }
        else if (relEvalFL == futureLevel) {
            benefit = maxFor(benefit, relEval, color());
            if (benefit != 0)
                addChance(benefit, futureLevel);
        }
        else {  // (relEvalFL > futureLevel)
            if (benefit != 0)
                addChance(benefit, futureLevel);
            if (isBetterThenFor(relEval, benefit, color())) {
                addChance(relEval, relEvalFL);
                benefit = relEval;
            }
        }
        return benefit;
    }

    boolean canCoverFromSavePlace() {
        boolean canCoverFromSavePlace = false;
        for (VirtualPieceOnSquare attackerAtLMO : getShortestReasonableUnconditionedPredecessors()) {
            if (attackerAtLMO == null || attackerAtLMO.getMinDistanceFromPiece().hasNoGo() )
                continue;
            if (attackerAtLMO.isASavePlaceToStay()) {
                canCoverFromSavePlace = true;
                debugPrint(DEBUGMSG_MOVEEVAL,"(save covering possible on " + squareName(attackerAtLMO.myPos) + ":) ");
                break;
            }
        }
        return canCoverFromSavePlace;
    }


    //////
    ////// general Piece/moving related methods



    /**
     * Where can a Piece go from here?
     * Similar to getPredecessorNeighbours(), but in the forward direction.
     * i.e. result is even identical for 1-hop-pieces, but for pawns completely the opposite...
     * For sliding pieces it only returns the direct sliding neighbours, not anything along the axis beyond the direct neighbour.
     * @return List of vPces that this vPce can reach.
     */
    // TODO: Should return Set or Unmodifiable Set
    protected abstract List<VirtualPieceOnSquare> getNeighbours();

    /**
     * Where can a Piece go from here?
     * Like getNeighbours() (result is even identical for 1-hop-pieces and pawns), but for
     * sliding pieces it not only returns the direct sliding neighbours, but all along the axis beyond the direct neighbour.
     * @return List of all vPces that this vPce can directly reach.
     */
    // TODO: Should return Set or Unmodifiable Set
    protected List<VirtualPieceOnSquare> getAllNeighbours() {
        return getNeighbours();
    }

    /**
     * where could my Piece come from? (incl. all options, even via NoGo)
     * For sliding pieces: does however not return all squares along the axes, just the squares from every direction
     * that provide the shortest way to come from that direction.
     * @return List of vPces (squares so to speak) that this vPce can come from
     */
    Set<VirtualPieceOnSquare> getPredecessors() {
        if (predecessors!=null)
            return predecessors;   // be aware, this is not a cache, it would cache too early, before distance calc is finished!
        return calcPredecessors();
    }

    abstract Set<VirtualPieceOnSquare> calcPredecessors();

    abstract Set<VirtualPieceOnSquare> calcDirectAttackVPcs();

    public Set<VirtualPieceOnSquare> getShortestReasonablePredecessorsAndDirectAttackVPcs() {
        Set<VirtualPieceOnSquare> both = new HashSet<VirtualPieceOnSquare>(getShortestReasonablePredecessors());
        both.addAll(getDirectAttackVPcs());
        return both;
    }

    public Set<VirtualPieceOnSquare> getShortestReasonableUncondPredAndDirectAttackVPcs() {
        Set<VirtualPieceOnSquare> both = new HashSet<VirtualPieceOnSquare>(getShortestReasonableUnconditionedPredecessors());
        both.addAll(getDirectAttackVPcs());
        return both;
    }


    void rememberAllPredecessors() {
        predecessors = calcPredecessors();
        directAttackVPcs = calcDirectAttackVPcs();
        shortestReasonableUnconditionedPredecessors = calcShortestReasonableUnconditionedPredecessors();
        shortestReasonablePredecessors = calcShortestReasonablePredecessors();
        firstMovesWithReasonableShortestWayToHere = calcFirstMovesWithReasonableShortestWayToHere();
    }

    /**
     * Subset of getPredecessorNeighbours(), with only those predecessors that can reasonably be reached by the Piece
     * and where there is no condition possibly avoiding the last move.
     * @return List of vPces that this vPce can come from.
     */
    Set<VirtualPieceOnSquare> getShortestReasonableUnconditionedPredecessors() {
        if (shortestReasonableUnconditionedPredecessors!=null)
            return shortestReasonableUnconditionedPredecessors;   // be aware, this is not a cache, it would cache to early, before distance calc is finished!
        return calcShortestReasonableUnconditionedPredecessors();
    }

    /**
     * Subset of getPredecessorNeighbours(), with only those predecessors that can reasonably be reached by the Piece
     * and where there is no condition possibly avoiding the last move.
     * @return List of vPces that this vPce can come from.
     */
    Set<VirtualPieceOnSquare> getDirectAttackVPcs() {
        if (directAttackVPcs!=null)
            return directAttackVPcs;   // be aware, this is not a cache, it would cache to early, before distance calc is finished!
        return calcDirectAttackVPcs();
    }

    /**
     * Subset of getPredecessorNeighbours(), with only those predecessors that can reasonably be reached by the Piece
     * @return List of vPces that this vPce can come from.
     */
    Set<VirtualPieceOnSquare> getShortestReasonablePredecessors() {
        if (shortestReasonablePredecessors!=null)
            return shortestReasonablePredecessors;   // be aware, this is not a cache, it would cache to early, before distance calc is finished!
        return calcShortestReasonablePredecessors();
    }


    abstract Set<VirtualPieceOnSquare> calcShortestReasonableUnconditionedPredecessors();

    abstract Set<VirtualPieceOnSquare> calcShortestReasonablePredecessors();

    /**
     * calc which 1st moves of my piece lead to here (on shortest ways) - obeying NoGos
     * @return */
    public Set<Move> getFirstMovesWithReasonableShortestWayToHere() {
        if (firstMovesWithReasonableShortestWayToHere !=null)
            return firstMovesWithReasonableShortestWayToHere;
        return calcFirstMovesWithReasonableShortestWayToHere();
    }

    /**
     * calc which 1st moves of my piece lead to here (on shortest ways) - obeying NoGos
     * @return */
    public Set<Move> calcFirstMovesWithReasonableShortestWayToHere() {
        final boolean localDebug = false; //DEBUGMSG_MOVEEVAL;
        debugPrint(localDebug, "getFirstMoveto:"+this.toString() + ": ");
        if (!getRawMinDistanceFromPiece().distIsNormal()) {
            return new HashSet<>();
        }
        Set<Move> res = new HashSet<>(8);
        if ( getRawMinDistanceFromPiece().dist()==1
                && !getRawMinDistanceFromPiece().hasNoGo() //!getMinDistanceFromPiece().hasNoGo()
              /*  || ( getRawMinDistanceFromPiece().dist()==2
                      && getRawMinDistanceFromPiece().nrOfConditions()==1) */ ) {
            res.add(new Move(getMyPiecePos(), myPos));  // a first "clean" move found
            if (localDebug)
                debugPrintln(localDebug, " found 1st move from "+ squareName(myPiece().getPos())
                    + " to " + squareName(myPos) + ": ");
        }
        else {
            // recursion necessary
            if (localDebug)
                debugPrintln(localDebug, " recursing down to " +
                    Arrays.toString(getShortestReasonableUnconditionedPredecessors()
                            .stream()
                            .map(vPce -> squareName(vPce.myPos))
                            .sorted(Comparator.naturalOrder())
                            .collect(Collectors.toList()).toArray()));
            for ( VirtualPieceOnSquare vPce : getShortestReasonableUnconditionedPredecessors() )  // getPredecessors() ) //
                if ( vPce!=this ) {
                    Set<Move> firstMovesToHere = vPce.getFirstMovesWithReasonableShortestWayToHere();
                    res.addAll(firstMovesToHere );
                }
        }
        return res;
    }

    private Set<Move> getMoveOrigin(VirtualPieceOnSquare vPce) {
        Set<Move> firstMovesToHere = vPce.getFirstMovesWithReasonableShortestWayToHere();
        if (firstMovesToHere==null) {
            firstMovesToHere = new HashSet<>();
            if ( rawMinDistance.dist()==1
                    || ( rawMinDistance.dist()==2 && !(rawMinDistance.nrOfConditions()==1) ) )
                firstMovesToHere.add(new Move(myPiece().getPos(), myPos));  // a first "clean" move found
        }
        return firstMovesToHere;
    }

    /**
     * calc which 1st moves of my piece lead to here (on shortest ways) - obeying NoGos
     * @return */
    public Set<Move> getFirstUncondMovesToHere() {
        //debugPrintln(true, "getFirstMoveto:"+this.toString() );
        switch (getRawMinDistanceFromPiece().dist()) {
            case 0 -> {
                return null;
            }
            case INFINITE_DISTANCE -> {
                return new HashSet<>();
            }
        }
        Set<Move> res = new HashSet<>(8);
        for ( VirtualPieceOnSquare vPce : getShortestReasonableUnconditionedPredecessors() ) {
            res.addAll(getUncondMoveOrigin(vPce));
        }
        return res;
    }

    private Set<Move> getUncondMoveOrigin(VirtualPieceOnSquare vPce) {
        /*if (vPce==null) {
            Set<Move> s = new HashSet<>();
            s.add(new Move(myPiece().getPos(), myPos));  // a first move found
            return s;
        }*/
        Set<Move> res = vPce.getFirstUncondMovesToHere();
        if (res==null) {
            res = new HashSet<>();
            if ( rawMinDistance.nrOfConditions()==0 && rawMinDistance.dist()==1 )
                res.add(new Move(myPiece().getPos(), myPos));  // a first "clean" move found
            // otherwise it is a conditional move found and cannot be directly moved.
        }
        return res;
    }



    //////
    ////// handling of Distances

    /** update methods for rawMinDistance - should never be set directly (as usual :-)
     * @param baseDistance copies the values from that CD
     */
    protected void updateRawMinDistanceFrom(ConditionalDistance baseDistance) {
        rawMinDistance.updateFrom(baseDistance);
        minDistsDirty();
        setLatestChangeToNow();
    }

    /**
     * just the same as the setter updateRMD(), but only updates if the given value is smaller
     *
     * @param baseDistance the value to compare and copy if smaller
     * @return boolean whether  value has changed
     */
    protected boolean reduceRawMinDistanceIfCdIsSmaller(ConditionalDistance baseDistance) {
        if (rawMinDistance.reduceIfCdIsSmaller(baseDistance)) {
            minDistsDirty();
            setLatestChangeToNow();
            return true;
        }
        return false;
    }

    public void pieceHasArrivedHere(int pid) {
        debugPrintln(DEBUGMSG_DISTANCE_PROPAGATION,"");
        debugPrint(DEBUGMSG_DISTANCE_PROPAGATION," ["+myPceID+":" );
        setLatestChangeToNow();
        // inform neighbours that something has arrived here
        board.getPiece(myPceID).startNextUpdate();
        if (pid==myPceID) {
            //my own Piece is here - but I was already told and distance set to 0
            assert (rawMinDistance.dist()==0);
            return;
        }
        // here I should update my own minDistance - necessary for same colored pieces that I am in the way now,
        // but this is not necessary as minDistance is safed "raw"ly without this influence and later it is calculated on top, if it is made "dirty"==null .
        // reset values from this square onward (away from piece)
        resetDistances();
        propagateResetIfUSWToAllNeighbours();

        // start propagation of new values
        quePropagateDistanceChangeToAllNeighbours();   //0, Integer.MAX_VALUE );
        /* ** experimenting with breadth search propagation ** */
        // no experimental feature any more, needed for pawns (and empty lists for others)
        // if (FEATURE_TRY_BREADTHSEARCH) {
            // continue one propagation by one until no more work is left.
        // distance propagation is not executed here any more any, but centrally hop-wise for all pieces
            /*int n = 0;
            while (myPiece().queCallNext())
                debugPrint(DEBUGMSG_DISTANCE_PROPAGATION, " " + (n++));
            debugPrintln(DEBUGMSG_DISTANCE_PROPAGATION, " done: " + n);*/
        //}
        /*debugPrint(DEBUGMSG_DISTANCE_PROPAGATION," // and complete the propagation for 2+: ");
        latestUpdate = myChessBoard.getPiece(myPceID).startNextUpdate();
        propagateDistanceChangeToOutdatedNeighbours(2, Integer.MAX_VALUE );
        */
        board.getPiece(myPceID).endUpdate();

        // TODO: Think&Check if this also works, if a piece was taken here
        debugPrint(DEBUGMSG_DISTANCE_PROPAGATION,"] ");
    }

    public void pieceHasMovedAway() {
        // inform neighbours that something has changed here
        // start propagation
        minDistsDirty();
        //setLatestChangeToNow();
        board.getPiece(myPceID).startNextUpdate();  //todo: think if startNextUpdate needs to be called one level higher, since introduction of board-wide hop-wise distance calculation
        if (rawMinDistance!=null && !rawMinDistance.isInfinite())
           quePropagateDistanceChangeToAllNeighbours(); // 0, Integer.MAX_VALUE);
        board.getPiece(myPceID).endUpdate();  // todo: endUpdate necessary?
    }

    // fully set up initial distance from this vPces position
    public void myOwnPieceHasSpawnedHere() {  //replaces myOwnPieceHasMovedHereFrom(int frompos) for spawn case. the normal case is replaced by orhestration viw chessPiece
        // one extra piece
        // treated just like sliding neighbour, but with no matching "from"-direction
        if (DEBUGMSG_DISTANCE_PROPAGATION) {
            debugPrintln(DEBUGMSG_DISTANCE_PROPAGATION, "");
            debugPrint(DEBUGMSG_DISTANCE_PROPAGATION, "[" + pieceColorAndName(board.getPiece(myPceID).getPieceType())
                    + "(" + myPceID + "): propagate own distance: ");
        }
        board.getPiece(myPceID).startNextUpdate();
        rawMinDistance = new ConditionalDistance(this,0);  //needed to stop the reset-bombs below at least here
        minDistsDirty();
        //initializeLocalMovenet(null);
        setAndPropagateDistance(new ConditionalDistance(this,0));  // , 0, Integer.MAX_VALUE );
        board.getPiece(myPceID).endUpdate();
    }

//    private void initializeLocalMovenet(VirtualPieceOnSquare closerNeighbour) {
//        if (closerNeighbour==null) {
            // I own/am the real piece
//            movenetCachedDistance = new MovenetDistance(new ConditionalDistance(0) );
//        }
        /* else {
            movenetCachedDistance = new MovenetDistance(new ConditionalDistance(
                            closerNeighbour.movenetDistance().movenetDist(),
                            1 ));
        } */
//        movenetNeighbours = getNeighbours();
//    }

    /**
     *  fully set up initial distance from this vPces position
     * @param frompos position where piece comes from.
     */
    public void myOwnPieceHasMovedHereFrom(int frompos) {
        assert(frompos!=NOWHERE);
        // a piece moved  (around the corner or for non-sliding neighbours
        // treated just like sliding neighbour, but with no matching "from"-direction
        debugPrintln(DEBUGMSG_DISTANCE_PROPAGATION, "");
        if (DEBUGMSG_DISTANCE_PROPAGATION)
            debugPrint(DEBUGMSG_DISTANCE_PROPAGATION, "[" + pieceColorAndName(board.getPiece(myPceID).getPieceType())
                + "(" + myPceID + "): propagate own distance: ");

        board.getPiece(myPceID).startNextUpdate();
        rawMinDistance = new ConditionalDistance(this,0);  //needed to stop the reset-bombs below at least here
        minDistsDirty();
        resetMovepathBackTo(frompos);
        //TODO: the currently necessary reset starting from the frompos is very costly. Try
        // to replace it with propagaten that is able to correct dist values in both directions
        VirtualPieceOnSquare vPceAtFrompos = board.getBoardSquare(frompos).getvPiece(myPceID);
        vPceAtFrompos.resetDistances();
        vPceAtFrompos.propagateResetIfUSWToAllNeighbours();
        setAndPropagateDistance(new ConditionalDistance(this,0));  // , 0, Integer.MAX_VALUE );

        board.getPiece(myPceID).endUpdate();
    }


    protected void recalcRawMinDistanceFromNeighboursAndPropagate() {
        //not necessary: minDistsDirty();
        /*if (getPieceID()==ChessBoard.DEBUGFOCUS_VP) {
            System.err.println("_.");
            System.err.println("");
            System.err.print("vPce.recalcRmdFromNeigAndProp-"+squareName(getMyPos())+":"+(getRawMinDistanceFromPiece()==null?" - ":(getRawMinDistanceFromPiece().isInfinite()?"X":getRawMinDistanceFromPiece().dist()))+": ");
        }*/

        if ( recalcRawMinDistanceFromNeighbours()!=0 )
            quePropagateDistanceChangeToAllNeighbours();   // Todo!: recalcs twice, because this propagate turns into a recalcAndPropagate for Pawns... must be revised
        else
            quePropagateDistanceChangeToUninformedNeighbours();
    }


    protected void resetMovepathBackTo(int frompos) {
        // most Pieces do nothing special here
    }

    public String getShortestInPathDirDescription() {
        return TEXTBASICS_NOTSET;
    }

    protected void resetDistances() {
        setLatestChangeToNow();
        if (rawMinDistance==null)
            rawMinDistance = new ConditionalDistance(this);
        else
            rawMinDistance.reset();
        minDistsDirty();
        resetBasics();
    }

    protected void minDistsDirty() {
        minDistance = null;
        suggestionTo1HopNeighbour = null;
        // TODO: check idea:
        //  if ("dist"==1)
        //      myPiece().bestMoveRelEvalDirty();
    }

    protected abstract void quePropagateDistanceChangeToAllNeighbours();

    protected abstract void quePropagateDistanceChangeToUninformedNeighbours();

    // not needed on higher level:  protected abstract void propagateDistanceChangeToOutdatedNeighbours();  //final int minDist, final int maxDist);

    // set up initial distance from this vPces position - restricted to distance depth change
    public abstract void setAndPropagateDistance(final ConditionalDistance distance);  //, final int minDist, final int maxDist );

    protected abstract int recalcRawMinDistanceFromNeighbours();

    protected abstract void propagateResetIfUSWToAllNeighbours();

    /**
     * myPiece()
     * @return backward reference to my corresponding real piece on the Board
     */
    public ChessPiece myPiece() {
        return board.getPiece(myPceID);
    }

    /**
     * mySquarePiece()
     * @return reference to the piece sitting on my square, or null if empty
     */
    ChessPiece mySquarePiece() {
        return board.getPieceAt(myPos);
    }

    boolean mySquareIsEmpty() {
        return board.isSquareEmpty(myPos);
    }

    // setup basic neighbourhood network
    public void addSingleNeighbour(VirtualPieceOnSquare newVPiece) {
        ((VirtualOneHopPieceOnSquare)this).addSingleNeighbour( (VirtualOneHopPieceOnSquare)newVPiece );
    }

    public void addSlidingNeighbour(VirtualPieceOnSquare neighbourPce, int direction) {
        ((VirtualSlidingPieceOnSquare)this).addSlidingNeighbour( (VirtualSlidingPieceOnSquare)neighbourPce, direction );
    }

    /**
     * tells the distance after moving away from here, considering if a Piece is in the way here
     * @return a "safe"=new ConditionalDistance
     */
/* experimental change, but brought bugs:
   public ConditionalDistance minDistanceSuggestionTo1HopNeighbour() {
        // Todo: Increase 1 more if Piece is pinned to the king
        if (rawMinDistance==null) {
            // should normally not happen, but in can be the case for completely unset squares
            // e.g. a vPce of a pawn behind the line it could ever reach
            return new ConditionalDistance();
        }

        if (suggestionTo1HopNeighbour!=null)  // good case: it's already calculated
            return suggestionTo1HopNeighbour;
        if (rawMinDistance.isInfinite())
            suggestionTo1HopNeighbour = new ConditionalDistance(INFINITE_DISTANCE);  // can't get further away than infinite...

        // TODO: the following increment doesn't work yet, because breadth propagation calls are already qued after the relEval is calculated
        //(getRelEval()==0 || getRelEval()==NOT_EVALUATED) ? 0 : MAX_INTERESTING_NROF_HOPS-1;

        // standard case: neighbour is one hop from here is
        suggestionTo1HopNeighbour = new ConditionalDistance(
                rawMinDistance,
                1,
                myPiece(),
                myPos,
                 ANY //to here unknown neighbour
                 );

        // TODO. check if my piece can move away at all (considering king pins e.g.)
        if (rawMinDistance.dist()==0) // that it here, as almost nothing is closer than my neighbour
            return suggestionTo1HopNeighbour;

        if (myChessBoard.hasPieceOfColorAt(myPiece().color(), myPos)) {
            // one of my same colored pieces are in the way: +1 more as this piece first has to move away
            int penalty = movingMySquaresPieceAwayDistancePenalty();
            suggestionTo1HopNeighbour.inc(penalty );
            // because own piece is in the way, we can only continue under the condition that it moves away
            suggestionTo1HopNeighbour.addCondition( mySquarePiece(), myPos, ANY);
        } else {
            // square is free (or of opposite color and to be beaten)
        }
        if (!evalIsOkForColByMin(getRelEval(), myPiece().color(), EVAL_TENTH))
            suggestionTo1HopNeighbour.setNoGo(myPos);
        return suggestionTo1HopNeighbour;
    }
*/
    public ConditionalDistance minDistanceSuggestionTo1HopNeighbour() {
        // Todo: Increase 1 more if Piece is pinned to the king
        if (rawMinDistance==null) {
            // should normally not happen, but in can be the case for completely unset squares
            // e.g. a vPce of a pawn behind the line it could ever reach
            return new ConditionalDistance(this);
        }
        // good case: it's already calculated
        if (suggestionTo1HopNeighbour!=null)
            return suggestionTo1HopNeighbour;

        if (rawMinDistance.dist()==0)
            suggestionTo1HopNeighbour = new ConditionalDistance(this,1);  // almost nothing is closer than my neighbour  // TODO. check if my piece can move away at all (considering king pins e.g.)
        else {
            // TODO: the following doesn't work yet, because breadth propagation calls are already qued after the relEval is calculated
            int inc = 0; //(getRelEval()==0 || getRelEval()==NOT_EVALUATED) ? 0 : MAX_INTERESTING_NROF_HOPS-1;

            if (rawMinDistance.isInfinite())
                suggestionTo1HopNeighbour = new ConditionalDistance(this);  // can't get further away than infinite...

                // one hop from here is +1 or +2 if this piece first has to move away
            else if (board.hasPieceOfColorAt(myPiece().color(), myPos)) {
                // one of my same colored pieces are in the way
                int penalty = movingMySquaresPieceAwayDistancePenalty();
                if (penalty<INFINITE_DISTANCE) {
                    inc += 1 + penalty;
                    suggestionTo1HopNeighbour = new ConditionalDistance(this,
                            rawMinDistance, inc,
                            myPos, ANYWHERE, myPiece().color());
                    checkNsetNoGoOrEnablingCondition(suggestionTo1HopNeighbour);
                    /*if ( getRawMinDistanceFromPiece().dist()>0 && isKillable() )
                        suggestionTo1HopNeighbour.setNoGo(myPos);*/
                } else
                    suggestionTo1HopNeighbour = new ConditionalDistance(this);
                // because own piece is in the way, we can only continue under the condition that it moves away
            } else {
                // square is free (or of opposite color and to be beaten)
                inc += 1; // so finally here return the "normal" case -> "my own Distance + 1"
                suggestionTo1HopNeighbour = new ConditionalDistance( this, rawMinDistance, inc);
                checkNsetNoGoOrEnablingCondition(suggestionTo1HopNeighbour);
                /* => not used for now. idea is interesting, but seems to induce other problems.
                if ( getRawMinDistanceFromPiece().dist()>0 && isKillable() )
                    suggestionTo1HopNeighbour.setNoGo(myPos); */
            }
        }
        return suggestionTo1HopNeighbour;
    }

    protected void checkNsetNoGoOrEnablingCondition(ConditionalDistance cd) {
        if ( !evalIsOkForColByMin(getRelEvalOrZero(), myPiece().color())
              //killedReasonablySure()
        ) {
            // is NoGo, but let's call it conditional if this is a doable first move
            // Todo!: does not work yet, bur is prereq. for not moving away from important squares (like to avoid mateIn1)
            // see results of v46 vs. 46a (where this was switched off)
            // (also not good: just avoid nogo, but add no condition)
            /*if (rawMinDistance.dist()==1
                    && rawMinDistance.isUnconditional()) {
                if ( !addEnablingThisSquareCondition(cd) )
                    cd.setNoGo(myPos);
            }
            else
             */
                cd.setNoGo(myPos);
        }
    }

    private boolean survivesReasonablySure() {
        return !evalIsOkForColByMin( getPriceToKill(), opponentColor(color()));
    }

    private boolean killedReasonablySure() {
        return isKillable() && evalIsOkForColByMin( getPriceToKill(), opponentColor(color()), -EVAL_HALFAPAWN);
    }

    private boolean addEnablingThisSquareCondition(ConditionalDistance cd) {
        // Todo: there can actually be several alternative conditions that fulfill this, but currently
        //  ConditionalDistance can handle only AND condiions not OR. So we pick only one here...
        int fromCond = board.getBoardSquare(myPos).getEnablingFromConditionForVPiece(this);
        if (fromCond!=NOWHERE) {
            cd.addCondition(fromCond, ANYWHERE, opponentColor(this.color()));
            return true;
        }
        return false;
    }


    long getLatestChange() {
        return latestChange;
    }

    public ConditionalDistance getRawMinDistanceFromPiece() {
        if (rawMinDistance==null) { // not set yet at all
            rawMinDistance = new ConditionalDistance(this);
            minDistsDirty();
        }
        return rawMinDistance;
    }

    /** tells if the rmd is already 1 or may become 1 by another move;
     * Either dist==1 -> Piece can directly move here (or it has a condition by the opponent, which does not count)
     * or dist==2, but 1 comes from a condition that I have to fulfill myself by moving a piece away.
     * @return true is so
     */
    public boolean rawMinDistanceIs1orSoon1() {
        return getRawMinDistanceFromPiece().dist() == 1
               || (getRawMinDistanceFromPiece().dist() == 2
                   && getRawMinDistanceFromPiece().nrOfConditions() == 1
                   && !getRawMinDistanceFromPiece().needsHelpFrom(myOpponentsColor()));
    }

    public ConditionalDistance getMinDistanceFromPiece() {
        // check if we already created the response object
        if (minDistance!=null)
            return minDistance;
        // no, its null=="dirty", we need a new one...
        // Todo: Increase 1 more if Piece is pinned to the king
        if (rawMinDistance==null) { // not set yet at all
            rawMinDistance = new ConditionalDistance(this);
            minDistsDirty();
        }
        minDistance = new ConditionalDistance(rawMinDistance);
        /* current decision: we do not use penalty or inc for mindistance if an own colored piece is in the way
         * because for threat/clash calculation it counts how the piece can get here.
         * (However, these movingAwayPenalties or other increases are calculated in the suggestions to further neighbours
         * old code was:
        else if (rawMinDistance.dist()==0
                || (rawMinDistance.dist()==INFINITE_DISTANCE) )
            minDistance=new ConditionalDistance(rawMinDistance);  // almost nothing is closer than my neighbour
        else {
            int inc = 0; // (getRelEval()==0 || getRelEval()==NOT_EVALUATED) ? 0 : MAX_INTERESTING_NROF_HOPS-1;
            // one hop from here is +1 or +2 if this piece first has to move away
            int penalty = movingMySquaresPieceAwayDistancePenalty();
            if (penalty>0)  // my own color piece, it needs to move away first
                minDistance = new ConditionalDistance(rawMinDistance,
                        penalty+inc,
                        myPos,
                        ANY);
            else  {
                // square is free or opponents piece is here, but then I can beat it
                minDistance = new ConditionalDistance(rawMinDistance, inc);
            }
        }
        */
        checkNsetNoGoOrEnablingCondition(minDistance);
        return minDistance;
    }

    /** returns the  penalty of how "hard" it is to move the own
     * piece away that is in the way here. returns 0 if there is no own piece here.
     */
    public int movingMySquaresPieceAwayDistancePenalty() {
        // TODO: this also depends on where a own mySquarePiece can move to - maybe only in the way?
        // looks if this square is blocked by own color (but other) piece and needs to move away first
        if (board.hasPieceOfColorAt( myPiece().color(), myPos )) {
            return mySquarePiece().movingAwayDistPenalty();
        }
        return 0;
    }

    @Override
    public String toString() {
        return "vPce("+myPceID+"="
                +(board.getPiece(myPceID)==null
                    ? "null!?"
                    : pieceColorAndName(board.getPiece(myPceID).getPieceType()) )
                +") on ["+ squareName( myPos)+"] "
                + rawMinDistance + " away from origin {"+ squareName( myPiece().getPos()) + "}";
    }

    public String getDistanceDebugDetails() {
        return  "";
        // (id=" + myPceID + ")" + ", latestChange=" + latestChange";
    }

    @Override
    public int compareTo(@NotNull VirtualPieceOnSquare other) {
        /* do not consider distance for std comparison:
        if ( this.getMinDistanceFromPiece().getShortestDistanceEvenUnderCondition()
                > other.getMinDistanceFromPiece().getShortestDistanceEvenUnderCondition() )
            return 2;
        if ( this.getMinDistanceFromPiece().getShortestDistanceEvenUnderCondition()
                < other.getMinDistanceFromPiece().getShortestDistanceEvenUnderCondition() )
            return -2;
        // distance is equal, so */
        // compare piece value
        return Integer.compare(abs(getValue()), abs(other.getValue()));
    }

    public int getValue() {
        return myPiece().getValue();
    }


/*    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        VirtualPieceOnSquare other = (VirtualPieceOnSquare) o;
        boolean equal = compareWithDebugMessage(this + ".Piece Type", myPceType, other.myPceType);
        equal &= compareWithDebugMessage(this + ".myPos", myPos, other.myPos);
        equal &= compareWithDebugMessage(this + "Relative Eval", relEval, other.relEval);
        equal &= compareWithDebugMessage(this + ".RawMinDistance", rawMinDistance, other.rawMinDistance);
        equal &= compareWithDebugMessage(this + ".minDistanceFromPiece", getMinDistanceFromPiece(), other.getMinDistanceFromPiece());
        equal &= compareWithDebugMessage(this + ".minDistanceSuggestionTo1HopNeighbour", minDistanceSuggestionTo1HopNeighbour(), other.minDistanceSuggestionTo1HopNeighbour());
        return equal;
    }
*/

    public String getPathDescription() {
        if (getRawMinDistanceFromPiece().dist()==0)
            return "-" + myPiece().symbol()+squareName(myPos);
        if (getRawMinDistanceFromPiece().dist()>=INFINITE_DISTANCE)
            return "[INF]";
        String tome =  "-" + squareName(myPos)
                +"(D"+getRawMinDistanceFromPiece()+")";
                //.dist()+"/"+getRawMinDistanceFromPiece().nrOfConditions()
        return  "[" + getShortestReasonableUnconditionedPredecessors().stream()
                .map(n-> "(" + n.getPathDescription()+ tome + ")")
                .collect(Collectors.joining( " OR "))
                + "]";
    }

    public String getBriefPathDescription() {
        debugPrintln(true,this.toString() );
        switch (getRawMinDistanceFromPiece().dist()) {
            case 0:
                return "-" + myPiece().symbol() + squareName(myPos);
            case INFINITE_DISTANCE:
                return "[INF]";
            default:
                String tome = "-" + squareName(myPos)
                        + "'" + getRawMinDistanceFromPiece().dist()
                        + "C" + getRawMinDistanceFromPiece().nrOfConditions();
                return "[" + getShortestReasonableUnconditionedPredecessors().stream()
                        .map(n -> n.getBriefPathDescription() + tome)
                        .collect(Collectors.joining("||"))
                        + "]";
        }
    }

    void resetJustChances() {
        chances = new EvalPerTargetAggregation(color());
        futureChances = null;
        moveAwayChances = new EvalPerTargetAggregation(color());
    }

    void resetBasics() {
        resetJustChances();
        clearCheckGiving();
        predecessors = null;
        resetPredecessors();
        mobilityFromHere = 0;
        mobilityMapFromHere = 0;
        resetKillable();
    }

    public void addMoveAwayChance(final int benefit, final int futureLevel, final int target) {
        if (futureLevel > MAX_INTERESTING_NROF_HOPS || abs(benefit) < 2)
            return;
        if (DEBUGMSG_MOVEEVAL && abs(benefit)>DEBUGMSG_MOVEEVALTHRESHOLD)
            debugPrintln(DEBUGMSG_MOVEEVAL," Adding MoveAwayChance of " + benefit + "@"+futureLevel+"$"+squareName(target)
                    +" of "+this+" on square "+ squareName(myPos)+".");
        moveAwayChances.add(benefit,futureLevel,target);
    }

    /**
     * add Chance of possible approaching (to eventually win) an opponents square (just the "upper hand"
     * or even with piece on it) with a certain benefit (relative eval, as always in board perspective)
     * in a suspected move distance.  The target for which this benefit finally is defaults to this vPce's this.myPos
     *
     * @param benefit
     * @param inFutureLevel
     */
    public void addChance(final int benefit, final int inFutureLevel) {
        addChance( benefit,  inFutureLevel, this.myPos );
    }

    /**
     * add Chance of possible approaching (to eventually win) an opponents square (just the "upper hand"
     * or even with piece on it) with a certain benefit (relative eval, as always in board perspective)
     * in a suspected move distance
     *
     * @param benefit
     * @param chanceFutureLevel
     * @param target helps to identify what the benefit was for. later when collecting the benefits from the vPces
     *               the maximum about these can be taken, instead og e.g. awarding 3 different options to achieve
     *               the same thing via the same starting move as 3x the benefit.
     */
    public void addChance(final int benefit, final int chanceFutureLevel, int target) {
        if (chanceFutureLevel>MAX_INTERESTING_NROF_HOPS
                || !getRawMinDistanceFromPiece().distIsNormal() )
                // Do not use || benefit==0) here. It is used for initial adding of moves...
            return;

        // with LowTide2, the chances will be collected and flow back down the distances later
        addRawChance( benefit , chanceFutureLevel, target);

        if (!evalIsOkForColByMin(benefit,color(), 0))
            return; // if benefit is negative (a fee/warning) then no counter measures are needed

        // add "counter chances" for all opponents moves hindering the first moves towards here
        Set<Move> firstMovesToHere = getFirstMovesWithReasonableShortestWayToHere();
        assert(firstMovesToHere!=null);
        // so still, wie Loop over the first moves, to see if there are countermeasures
        for (Move fm : firstMovesToHere) {
            if ( !evalIsOkForColByMin( benefit, myPiece().color(), -EVAL_DELTAS_I_CARE_ABOUT) )
                continue;
            //TODO: always search for all counter moves here after every addChance is ineffective.
            // Should be done later collectively after all Chances are calculated
            // a positive move - see who can cover this square
            Square toSq = board.getBoardSquare(fm.to());
            VirtualPieceOnSquare vPceAtToSq = toSq.getvPiece(getPieceID());
            final int inFutureLevel = (chanceFutureLevel == 0)
                    ? vPceAtToSq.getStdFutureLevel()  // need to get here
                    : ((vPceAtToSq.getAttackingFutureLevelPlusOne()-1)+1);     // might be enough to attack/defend here
            int counterBenefit = -benefit >> 1;
            int oppHelpersNeeded = vPceAtToSq.getRawMinDistanceFromPiece()
                                        .countHelpNeededFromColorExceptOnPos(myOpponentsColor(), getMyPos());
            if ( oppHelpersNeeded > 0) {
                // the benefit is only possibly with the opponents help (moving out of the way)
                if ( inFutureLevel <= 1
                        && oppHelpersNeeded == 1  // 47u22-47u66, was >= 1
                        && vPceAtToSq.getRawMinDistanceFromPiece().nrOfConditions() == 1 ) {
                    // there is only exactly one in the way of an otherwise direct attack
                    int fromCond = vPceAtToSq.getRawMinDistanceFromPiece().getFromCond(0);
                    if (fromCond>=0) {
                        ChessPiece blocker = board.getPieceAt(fromCond);
                        if (blocker!=null) {
                            if (DEBUGMSG_MOVEEVAL && abs(benefit) > DEBUGMSG_MOVEEVALTHRESHOLD)
                                debugPrint(DEBUGMSG_MOVEEVAL, "Telling " + blocker + " to stay: ");
                            blocker.addMoveAwayChance2AllMovesUnlessToBetween(
                                    benefit >> 1, 0,
                                    fm.to(), getMyPiecePos(), false,
                                    fm.to() );
                        }
                    }
                }
                counterBenefit >>= 3;
            }
            // iterate over all opponents who could sufficiently cover my target square.
            if (toSq.isEmpty() ) {   // but only to this if square is empty, because otherwise (clash) this is already calculated by "close future chances"
                int myattacksAfterMove = toSq.countDirectAttacksWithColor(color());
                if (!(colorlessPieceType(getPieceType()) == PAWN && fileOf(fm.to()) == fileOf(fm.from())))  // not a straight moving pawn
                    myattacksAfterMove--;   // all moves here (except straight pawn) take away one=my cover from the square.
                for (VirtualPieceOnSquare opponentAtTarget : toSq.getVPieces()) {
                    if (opponentAtTarget != null
                            && opponentAtTarget.color() != color()
                            && !opponentAtTarget.getRawMinDistanceFromPiece().isInfinite()
                            && opponentAtTarget.coverOrAttackDistance() > 1 // if it is already covering it, no need to bring it closer...
                            && ! ( ( isPawn(opponentAtTarget.getPieceType())
                                    && ((VirtualPawnPieceOnSquare)opponentAtTarget).lastPawnMoveIsStraight() )
                                 )
                    ) {
                        // loop over all positions from where the opponent can attack/cover this square
                        for (VirtualPieceOnSquare opponentAtLMO : opponentAtTarget.getShortestReasonableUnconditionedPredecessors()) {
                            if (opponentAtLMO == null
                                    || ( isPawn(opponentAtTarget.getPieceType())
                                            && (fileOf(opponentAtTarget.getMyPos()) == fileOf(opponentAtLMO.getMyPos()) ) ) // last move from her would be a straight pawn move, which is not covering
                            )
                                continue;
                            ConditionalDistance oppAtLMORmd = opponentAtLMO.getRawMinDistanceFromPiece();
                            int defendBenefit = abs(counterBenefit);
                            int opponendDefendsAfterMove = toSq.countDirectAttacksWithColor(opponentAtTarget.color()) + 1;  // one opponent was brought closer
                            // TODO! real check if covering is possible/significant and choose benefit accordingly
                            // here just a little guess...
                            if (opponendDefendsAfterMove >= myattacksAfterMove)
                                defendBenefit >>= 2;
                            // not anymore, because of forking square coverage with higher benefit: limit benefit to the attacking pieces value (as long as we do not use real significance/clash calculation here)
                            // defendBenefit = min(defendBenefit, positivePieceBaseValue(getPieceType()));
                            if (!oppAtLMORmd.isUnconditional()  // is conditional and esp. the last part has a condition (because it has more conditions than its predecessor position)
                                    && oppAtLMORmd.nrOfConditions() > oppAtLMORmd.oneLastMoveOrigin().getRawMinDistanceFromPiece().nrOfConditions())
                                defendBenefit >>= 2;
                            int defendInFutureLevel = opponentAtLMO.getStdFutureLevel();  //was: +1, but shouldn't without +1 already be enough to cover the target sq
                                    // (opponentAtLMO.color() == board.getTurnCol() ? 1 : 0);
                            if (defendInFutureLevel < 0)
                                defendInFutureLevel = 0;
                            if (defendInFutureLevel > MAX_INTERESTING_NROF_HOPS + 1
                                    || getRawMinDistanceFromPiece().dist() < oppAtLMORmd.dist() - 3
                                    || defendInFutureLevel > inFutureLevel+1
                                   // || ( isPawn(opponentAtLMO.getPieceType())
                                   //      && !((VirtualPawnPieceOnSquare)opponentAtTarget).lastMoveIsStraight() )
                            )
                                continue;
                            if (getRawMinDistanceFromPiece().dist() < oppAtLMORmd.dist())
                                defendBenefit >>= 1;
                            if (opponentAtLMO.getRawMinDistanceFromPiece().hasNoGo())
                                defendBenefit >>= 3;  // could almost continue here
                            if (opponentAtLMO.getMinDistanceFromPiece().hasNoGo())
                                defendBenefit >>= 1;  // and will not survive there myself
                            if (this.getMinDistanceFromPiece().hasNoGo())
                                defendBenefit >>= 2;  // if the piece dies there anyway, extra coverage is hardly necessary
                            if (isKing(opponentAtLMO.getPieceType())) {
                                if (oppAtLMORmd.dist() > 1 || isQueen(getPieceType()))
                                    continue;
                                if (oppAtLMORmd.dist() > 2)
                                    defendBenefit >>= 2;
                                else
                                    defendBenefit >>= 1;
                            }
                            int finalFutureLevel = inFutureLevel - 1 - defendInFutureLevel;
                            if (finalFutureLevel < 0 ) {  // defender is too late...
                                finalFutureLevel = -finalFutureLevel;
                                defendBenefit /= 3 + finalFutureLevel;
                                if (DEBUGMSG_MOVEEVAL && abs(defendBenefit) >  DEBUGMSG_MOVEEVALTHRESHOLD)
                                    debugPrint(DEBUGMSG_MOVEEVAL, " (too late but anyway:) ");
                            }
                            else if (finalFutureLevel>0) // still time
                                defendBenefit >>= finalFutureLevel;
                            if (isBlack(opponentAtLMO.color()))
                                defendBenefit = -defendBenefit;
                            if (abs(defendBenefit) > DEBUGMSG_MOVEEVALTHRESHOLD) {
                                if (DEBUGMSG_MOVEEVAL)
                                    debugPrint(DEBUGMSG_MOVEEVAL, " countermoves against target: ");
                                opponentAtLMO.addRawChance(defendBenefit, chanceFutureLevel, target); //max(inFutureLevel, defendInFutureLevel));
                                if (finalFutureLevel<chanceFutureLevel) {
                                    final int immediateBlockerBenefit = (defendBenefit >> 3) / (1 + chanceFutureLevel - finalFutureLevel);
                                    if (abs(immediateBlockerBenefit) > 1)
                                        opponentAtLMO.addRawChance(immediateBlockerBenefit, finalFutureLevel, target); //max(inFutureLevel, defendInFutureLevel));
                                }
                            }
                        }
                    }
                }
            }
            // and see who can block the firstmove
            if (inFutureLevel<=4) {
                int blockingBenefit = -benefit >>2;  //  /2 because assigned at least 2 times +
                //if (inFutureLevel==0)
                //    blockingBenefit >>= 1;
                //else
                if (inFutureLevel>=3)
                    blockingBenefit >>= (inFutureLevel-1);
                toSq.getvPiece(getPieceID()).addBenefitToBlockers(fm.from(), chanceFutureLevel, blockingBenefit, target );
            }
            if (DEBUGMSG_MOVEEVAL && abs(benefit)>DEBUGMSG_MOVEEVALTHRESHOLD)
                debugPrintln(DEBUGMSG_MOVEEVAL, ".");
            /* Option:Solved differently in loop over allsquares now
            ConditionalDistance toSqRmd = toSq.getvPiece(myPceID).getRawMinDistanceFromPiece();
            if ((toSqRmd.dist() == 1 || toSqRmd.dist() == 2) && toSqRmd.nrOfConditions() == 1) {
                // add chances for condition of this "first" (i.e. second after condition) move, that make me come one step closer
                int fromCond = getRawMinDistanceFromPiece().getFromCond(0);
                if (fromCond != -1)
                    addChances2PieceThatNeedsToMove(benefit - (benefit >> 2), inFutureLevel, fromCond);
            } */
        }
        // add chances for other moves on the way fulfilling conditions, that make me come one step closer
        // Todo: add conditions from all shortest paths, this here covers only one, as conditions are only stored along one of the shortests paths
        //  Partially solved above by addChances2PieceThatNeedsToMove fir first moves.
                /* Option:Solved differently in loop over allsquares now
        if (getRawMinDistanceFromPiece().dist()>1)
            for (Integer fromCond : rawMinDistance.getFromConds() ) {
                if (fromCond!=-1)
                    addChances2PieceThatNeedsToMove(benefit>>1, inFutureLevel, fromCond);
            } */
    }

    /**
     * like addChance() but not "calling" other opponents pieces to cover here if benefit is significant
     * @param benefit
     * @param futureLevel
     */
    public void addRawChance(final int benefit, final int futureLevel, int target) {
        if (futureLevel>MAX_INTERESTING_NROF_HOPS
                || !getRawMinDistanceFromPiece().distIsNormal())
            return;

        // with LowTide2, the chances will later flow back down the distances, so no need to set the chance per firstMoveHete
        /*Move m = new Move(ANYWHERE,getMyPos()); // from anywhere to here.
        if ( rawMinDistanceIs1orSoon1() ) {
            m.setFrom(getMyPiecePos());
        } */
        addChanceLowLevel( benefit , futureLevel, target);
        /* this change brings up todos:
        todo: adapt fork detection - it is no longer working
              (missing: " 103@0 danger moving vPce(2=schwarze Dame) on [b6] 1 ok away from origin {d8} into possible fork on square b6 by vPce(7=weißer Läufer) on [d4] 1 ok away from origin {g7}. ->d8b6(103@0)")
         */

        /* pre TideEval2 code:
        // add chances for all first move options to here
        Set<Move> firstMovesToHere = getFirstMovesWithReasonableShortestWayToHere();
        if (firstMovesToHere==null) {
            board.internalErrorPrintln("no first moves found for " + this + ".");
            return;
        }
        if ( DEBUGMSG_MOVEEVAL && !evalIsOkForColByMin(benefit, color(), -1) && futureLevel>1 )
            debugPrint(DEBUGMSG_MOVEEVAL, " (Problem: negative benefit on high futureLevel:)");

        for (Move m : firstMovesToHere) {   // was getFirstUncondMovesToHere(), but it locks out enabling moves if first move has a condition
            if (abs(benefit)>4)
                debugPrint (DEBUGMSG_MOVEEVAL, " +raw->" + m + "(" + benefit + "@" + futureLevel + ") ");
            addChanceLowLevel( benefit , futureLevel, m, myPos );
        }
        */
    }


    /** step 1 in aggregating down chances from vPces further away
     * @param chances an additional chance at one distance further.
     */
    public void aggregateInFutureChances(EvalPerTargetAggregation chances) {
        if (futureChances == null) {
            // this is the first future chance I am informed about, let's just copy it...
            futureChances = new EvalPerTargetAggregation(chances);
            forkingChance = 0;
            return;
        }
        if (chances.size() == 0)
            return;

        // detect forking
        int forkingAtLevel = getStdFutureLevel() + 1;  // only relevant at dist+1, as only then it is forcing
        if (forkingAtLevel < MAX_INTERESTING_NROF_HOPS) { // why is this safety net needed? ok, for d==MAX there is no fork at MAX+1, but it is also triggered, for infinite/unevaluated distances here: should this be possible? (it occurs in the data, but is there a bug earlier?)
            int newDirectChance = chances.getAggregatedEval().getEvalAt(forkingAtLevel);
            if (evalIsOkForColByMin(newDirectChance, color(), -EVAL_TENTH)) {
                int directChanceByNow = futureChances.getAggregatedEval().getEvalAt(forkingAtLevel);
                if (isBetterThenFor(newDirectChance, directChanceByNow, color())) {
                    if (isBetterThenFor(directChanceByNow, forkingChance, color()))
                        forkingChance = directChanceByNow;  // remember the new 2nd best
                } else if (isBetterThenFor(newDirectChance, forkingChance, color())) {
                    forkingChance = newDirectChance;  // remember the new 2nd best
                }
            }
        }
        else if (DEBUGMSG_MOVEEVAL && getRawMinDistanceFromPiece().dist()>MAX_INTERESTING_NROF_HOPS)
            debugPrintln(DEBUGMSG_MOVEEVAL, "trying to aggregate chance " + chances + " into far away (?) vPce: " + this + ".");

        // regular aggregation (does not care about forks, but max'es for same targets
        futureChances.aggregateIn(chances, isKillableReasonably());  // consider: can I really benefit from here on, or will I be exchanged before that... however. do not set to 0 as this would take away chances of indirect help, whre killing this pce stuill eliminates an opponent that would have been needed to attack the target I protect (but this is too complex to check here)
    }

    /** step 2 in aggregating down chances from vPces further away.
     * must be called after all calls to aggregateFutureChances().
     * Includes fork detection.
     */
    public void consolidateChances() {
        // future chances can all be taken over directly (their future levels already fit)
        chances.aggregateIn(futureChances);
        // then add the forking chances on my futurelevel - it was already implicitely calculated when the
        // futureChances were collected
        int forkFutureLevel =  getStdFutureLevel();
        if (forkFutureLevel < MAX_INTERESTING_NROF_HOPS
                && abs(forkingChance) > EVAL_HALFAPAWN ) {
            int realForkingChance = minFor(forkingChance, getPriceToKill(), color());
            if (getMinDistanceFromPiece().hasNoGo())
                realForkingChance >>= 3;
            realForkingChance >>= getMinDistanceFromPiece().countHelpNeededFromColorExceptOnPos(opponentColor(color()), NOWHERE);
            if ( evalIsOkForColByMin(realForkingChance, color(), -EVAL_HALFAPAWN) ) {
                if (DEBUGMSG_MOVEEVAL)
                    debugPrintln(DEBUGMSG_MOVEEVAL, "Fork opportunity of " + forkingChance + "@"+ forkFutureLevel
                            + ( (getMinDistanceFromPiece().hasNoGo()?" (Nogo-reduced)" : ""))
                            + " (with priceToKill="+ getPriceToKill() + ") found for " + this + ".");
                chances.add(realForkingChance, forkFutureLevel, getMyPos());
                /*board.internalErrorPrintln("INFO:" + "Fork opportunity of " + forkingChance + "@"+ forkFutureLevel
                        + ( (getMinDistanceFromPiece().hasNoGo()?" (Nogo-reduced)" : ""))
                        + " (with priceToKill="+ getPriceToKill() + ") found for " + this + ".");*/
            } else {
                if (DEBUGMSG_MOVEEVAL)
                    debugPrintln(DEBUGMSG_MOVEEVAL, "No real forking opportunity of " + forkingChance + "@"+ forkFutureLevel
                            + " due to good priceToKill="+ getPriceToKill() + " found for " + this + ".");
            }
        }
    }

    /**
     * adds Checks to piece2Bmoved. Called at the target (king)
     * @param piece2BmovedPos
     */
    void addCheckFlag2PieceThatNeedsToMove(final int piece2BmovedPos) {
        ChessPiece piece2Bmoved = board.getPieceAt(piece2BmovedPos);
        if (piece2Bmoved==null) {
            if (DEBUGMSG_MOVEEVAL)
                board.internalErrorPrintln("Error in from-condition for setting check flag of " + this + ": points to empty square " + squareName(piece2BmovedPos));
            return;
        }
        piece2Bmoved.addChecking2AllMovesUnlessToBetween(
                        myPiece().getPos(),
                        myPos, this );
    }


    /**
     * adds Chances to piece2Bmoved, but also threats that come up, when a piece in my
     * way (at piece2movedPos) moves away.
     *
     * @param benefit
     * @param inOrderNr
     * @param piece2BmovedPos
     * @return
     */
    int addChances2PieceThatNeedsToMove(int benefit, int inOrderNr, final int piece2BmovedPos) {
        ChessPiece piece2Bmoved = board.getPieceAt(piece2BmovedPos);
        int counter = 0;
        if (piece2Bmoved==null) {
            if (DEBUGMSG_MOVEEVAL)
                board.internalErrorPrintln("Error in from-condition of " + this + ": points to empty square " + squareName(piece2BmovedPos));
        }
        else {
            if (color() != piece2Bmoved.color() && inOrderNr>0)
                inOrderNr--;
            // find matching lastMoveOrigins, which are blocked by this piece
            for (VirtualPieceOnSquare lmo : getPredecessors()) {
                if ( calcDirFromTo(myPos, lmo.myPos) == calcDirFromTo(myPos, piece2BmovedPos)
                     && calcDirFromTo(myPos, piece2BmovedPos) != NONE
                ) {
                    if (lmo.getMyPos() == piece2BmovedPos)
                        continue; // would be beating and moving on, but piece2Bmoved is moving away in this scenario
                    // origin is in the same direction
                    Set<Move> firstMoves = lmo.getFirstMovesWithReasonableShortestWayToHere();
                    if ( (firstMoves==null || firstMoves.size()==0) ) {
                        if (lmo.getMinDistanceFromPiece().dist() == 0) { // ?? was ==1 but this seems wrong...
                            firstMoves.add(new Move(lmo.myPos, myPos));  // there is no lmo of the lmo, it is a 1-dist move
                            //board.internalErrorPrintln("BLUP: " + this + " , " + lmo + " .");
                        }
                    }
                    if (firstMoves.size()==1 && lmo.getRawMinDistanceFromPiece().dist() >= 1)
                        benefit >>= 1;  // only one move leads to here, we also look at the first move and the other half is given out below
                    if ( isBetweenFromAndTo(piece2BmovedPos, lmo.myPos,myPos ) ) {
                        counter = max(counter,
                            piece2Bmoved.addMoveAwayChance2AllMovesUnlessToBetween(
                                benefit, inOrderNr,
                                lmo.myPos, myPos, // to the target position
                                (piece2Bmoved.color() != color())
                                        && (lmo.getRawMinDistanceFromPiece().dist() >= 1
                                        || evalIsOkForColByMin(benefit, piece2Bmoved.color())),  // an opponents piece moving to the hop/turning point
                                // before my target is also kind of moving out of
                                // the way, as it can be beaten  (unless it beats me)
                                getMyPos()));
                    }
                    // if there is only one way to get here, the following part works in the same way for the first move
                    // to here (but any hop in between is neglected, still)
                    // thus, TODO!: exclusion needs to be extended to previous moves on the way, works only for the last part (or 1-move distance)
                    // e.g. pawn moving straigth in front of rook is still given "move out of the way" bonus for the second part of the journey, where it does not move "in between".
                    // TODO!!!: partial solution is easier: do not call addMoveAwayChance2AllMovesUnlessToBetween() if fromPos is not in the way of the relevant section.
                    if ( firstMoves.size() != 1 || lmo.getRawMinDistanceFromPiece().dist() < 1 )
                        continue;
                    int nextToPos = firstMoves.iterator().next().to(); // to the target position

                    if ( !isBetweenFromAndTo(piece2BmovedPos, getMyPiecePos(), nextToPos ) )
                        continue;
                    if (DEBUGMSG_MOVEEVAL)
                        debugPrint(DEBUGMSG_MOVEEVAL,"...for " + piece2Bmoved + ": ");
                    counter = max(counter,
                        piece2Bmoved.addMoveAwayChance2AllMovesUnlessToBetween(
                            benefit, inOrderNr,
                            getMyPiecePos(),nextToPos,
                            (piece2Bmoved.color() != color())
                                    && evalIsOkForColByMin(benefit, piece2Bmoved.color()),  // exclude blocking my own color. but also exclude an opponents piece moving to = beating my piece point also gets credit, but not warning (as it eliminates the reason)
                            getMyPos()));
                }
            }
            // TODO: Check if toPos here should really be exclusive or rather inclusive, because if the p2Bmoved is
            // moving just there (propably beating) then the benefit for the other piece if most probably gone.
        }
        return counter;
    }

    private void addChanceLowLevel(final int benefit, int futureLevel, final int target) {
        if (futureLevel<0 || futureLevel>MAX_INTERESTING_NROF_HOPS) {
            if (DEBUGMSG_MOVEEVAL)
                board.internalErrorPrintln("Error in addChance for " + this + ": invalid futureLevel in benefit " + benefit + "@" + futureLevel);
            return;
        }
        //if ( DEBUGMSG_MOVEEVAL && !evalIsOkForColByMin(benefit, color(), -1) && futureLevel>1 )
        //    debugPrintln(DEBUGMSG_MOVEEVAL, " (Problem: negative benefit "+benefit+"@"+futureLevel+" on high futureLevel for "+ this + ")");

        chances.add(benefit,futureLevel,target);
        if (abs(benefit)>DEBUGMSG_MOVEEVALTHRESHOLD)
            debugPrintln (DEBUGMSG_MOVEEVAL, " (->addChance " + benefit + "@" + futureLevel + "$"+squareName(target)
                    + " for " +this +") " );
    }

    /*
    private void addChanceLowLevel(final int benefit, int futureLevel, final Move m, final int target) {
        if (futureLevel<0 || futureLevel>MAX_INTERESTING_NROF_HOPS) {
            if (DEBUGMSG_MOVEEVAL)
                board.internalErrorPrintln("Error in addChance for " + this + ": invalid futureLevel in benefit " + benefit + "@" + futureLevel);
            return;
        }
        if ( DEBUGMSG_MOVEEVAL && !evalIsOkForColByMin(benefit, color(), -1) && futureLevel>1 )
            debugPrintln(DEBUGMSG_MOVEEVAL, " (Problem: negative benefit "+benefit+"@"+futureLevel+" on high futureLevel for "+ this + ")");

        if (DEBUGMSG_MOVEEVAL_INTEGRITY && m.from() != getMyPiecePos() )
            board.internalErrorPrintln("Problem in addChanceLowLevel: trying to add " + m + " to " + this + ".");

        EvaluatedMove addEM = new EvaluatedMove(m, target);
        addEM.addEval(benefit, futureLevel );
        if ( myPiece().isBasicallyALegalMoveForMeTo(m.to()) ) { // dist==1 but illegal move (still)
            addEM.setBasicallyLegal();
        }
        // else impossible move, square occupied.
        //    Still move needs to be entered in chance list, so that moving away from here also gets calculated
        //    and to be able to calculate consequences if this move gets enabled

        EvaluatedMove chanceSumUpToNow = chances.get(addEM.hashId());
        if (chanceSumUpToNow==null) {
            chances.put(addEM.hashId(), addEM);
        }
        else {
              //  chances.get(futureLevel).replace(m, chanceSumUpToNow + benefit);
            chanceSumUpToNow.addEval(addEM.eval());
        }
    }*/

    public EvalPerTargetAggregation getChances() {
        return chances;
    }

    public EvalPerTargetAggregation getLocalChances() {
        return chances.filterTarget(getMyPos());
    }



    /**
     * get sum of chances at a certain level
     * @param futureLevel
     * @return sum of chances at futureLevel
     */
    private int getChanceAtLevel(int futureLevel) {
        return getChance().getEvalAt(futureLevel);
    }

    /**
     * get sum of chances towards all targets here and from here
     * @return sum of chances
     */
    Evaluation getChance() {
        return chances.getAggregatedEval();
    }

    /**
     * get sum of moveAwayChances towards all targets here and from here
     * @return sum of chances
     */
    Evaluation getMoveAwayChance() {
        return moveAwayChances.getAggregatedEval();
    }


    public int getClosestChanceReachout() {
        //TODO!!!!: re-implementation! as type + impl. of chances was completely changed ...
        /*
        somthing like
        int min = 0;
        for (Integer c : chances.get(inFutureLevel).values() ) {
            if (isWhite(color()) ? c.intValue() > max
                    : c.intValue() < max)
                max = c.intValue();
        }
        return max;
         */
        return 0;
    }

    /**
     * isUnavoidableOnShortestPath() finds out if the square pos has to be passed on
     * the way from the piece to this current square/cPiece.*
     *
     * @param pos      that is checked, if it MUST be on the way to here
     * @param maxdepth remaining search depth limit - needed to cancel long possible
     *                 detours, e.g. due to MULTIPLE shortest paths. (! Also needed because
     *                 remaining bugs in dist-calculation unfortunately lets sometimes
     *                 exist circles in the shortest path, leading to endless recursions...)
     * @return true, if all paths between actual piece and here lead via pos.
     */
    abstract public boolean isUnavoidableOnShortestPath(int pos, int maxdepth);

    //TODO: for all killablaOnTheWay-Methods - use more efficient approach (e.g. included in dist calculation) or at least caching.
    public int getLowestPriceToKillOnTheWayHere() {
        // 0 when NoGo
        if (getMinDistanceFromPiece().dist() == 0)
            return getPriceToKill();
        // recursively determine minimum of priceToKill from predecessors which are not NoGo
        int min = isKillable() ? getPriceToKill() : 0;
        for(VirtualPieceOnSquare p : getShortestReasonablePredecessors()) {
            if (p.getRawMinDistanceFromPiece().dist() == 0)
                continue; // we are interested in moving pieces, not the starting place.
            if (!p.isKillableOnTheWayHere() && !p.isKillable())
                return min;  // not killable via this predecessor
            int pLP2K = p.getLowestPriceToKillOnTheWayHere();
            minFor(pLP2K, min, myOpponentsColor());
        }
        return min;
    }

    public boolean isReasonablyKillableOnTheWayHere() {
        if (getMinDistanceFromPiece().dist() == 0)
            return false; // as we are interested in moving pieces, being killable at the current position does not make it killable... so not: isKillableReasonably();
        // recursively determine minimum of priceToKill from predecessors which are not NoGo
        for(VirtualPieceOnSquare p : getShortestReasonablePredecessors()) {
            if (!p.isReasonablyKillableOnTheWayHere() && !isKillableReasonably())
                return false;
        }
        return true;
    }

    public boolean isKillableOnTheWayHere() {
        if (getMinDistanceFromPiece().dist() == 0)
            return false; // as we are interested in moving pieces, being killable at the current position does not make it killable... so not: isKillable();
        // recursively determine minimum of priceToKill from predecessors which are not NoGo
        for(VirtualPieceOnSquare p : getShortestReasonablePredecessors()) {
            if (!p.isKillableOnTheWayHere() && !p.isKillable())  // there is at least one way in where piece is not killable
                return false;
        }
        return true;
    }

    //// getter

    public int getPieceID() {
        return myPceID;
    }

    public int getPieceType() {
        return myPceType;
    }

    protected long getOngoingUpdateClock() {
        return board.getPiece(myPceID).getLatestUpdate();
    }

    public int getRelEval() {
        return relEval;
    }

    public int getRelEvalOrZero() {
        return hasRelEval() ? relEval : 0;
    }

    public int getClashContribOrZero() {
        return relClashContrib == NOT_EVALUATED ? 0 : relClashContrib;
    }

    public boolean hasRelEval() {
        return relEval != NOT_EVALUATED;
    }

    public boolean color() {
        return colorOfPieceType(myPceType);
    }

    protected boolean myOpponentsColor() {
        return opponentColor(myPiece().color());
    }

    public int getMyPiecePos() {
        return board.getPiece(myPceID).getPos();
    }

    public boolean isConditional() {
        return !rawMinDistance.isUnconditional();
    }

    public boolean isUnconditional() {
        return rawMinDistance.isUnconditional();
    }

    public boolean isCheckGiving() {
        return isCheckGiving || hasAbzugChecker();
    }

    public boolean isRealChecker() {
        return isCheckGiving;
    }

    public VirtualPieceOnSquare getAbzugChecker() {
        return abzugChecker;
    }

    public boolean hasAbzugChecker() {
        return abzugChecker != null;
    }

    public int getMobility() {
        return mobilityFromHere;
    }

    public int getMobilityMap() {
        return mobilityMapFromHere;
    }

    public int getPriceToKill() {
        return priceToKill;
    }

    public boolean isKillable() {
        return killable;
    }

    public boolean isKillableReasonably() {
        return isKillable() && evalIsOkForColByMin(getPriceToKill(), myOpponentsColor());
    }

    public boolean relEvalIsReasonable() {
        if (!hasRelEval())
            return false;
        return evalIsOkForColByMin(getRelEval(), color());
    }

    //// setter

    public void setPriceToKill(int priceToKill) {
        this.priceToKill = priceToKill;
    }


    /**
     * sets that this vPce is killable (in a reasonable way for the opponent).
     * It alsi sets the "price" for that - as a default - to the current releval.
     */
    public void setKillable() {
        this.killable = true;
        setPriceToKill(getRelEvalOrZero());
        minDistsDirty();
    }

    public void setKillable(boolean killable) {
        this.killable = killable;
        minDistsDirty();
    }

    public void resetKillable() {
        setKillable(false);
        setPriceToKill(0);
    }

    protected void setLatestChangeToNow() {
        latestChange = getOngoingUpdateClock();
    }

    public void setRelEval(final int relEval) {
        int oldRelEval = this.relEval;
        this.relEval = relEval;
        /*if (relEval!=NOT_EVALUATED)
            addChance(relEval - (oldRelEval==NOT_EVALUATED ? 0 : oldRelEval), 0);*/
        if (oldRelEval-2<=relEval && oldRelEval+2>=relEval)  // +/-2 is almost the same.
            return;
        updateDistsAfterRelEvalChanged();
    }

    public void addRelEval(final int relEvalDelta) {
        int oldRelEval = this.relEval;
        this.relEval += relEvalDelta;
        /*if (relEval!=NOT_EVALUATED)
            addChance(relEval - (oldRelEval==NOT_EVALUATED ? 0 : oldRelEval), 0);*/
        if (abs(relEvalDelta)<=2)  // +/-2 is almost the same.
            return;
        updateDistsAfterRelEvalChanged();
    }

    public void clearCheckGiving() {
        isCheckGiving = false;
        abzugChecker = null;
    }


    private void updateDistsAfterRelEvalChanged() {
        //distances need potentially to be recalculated, as a bad relEval can influence if a piece can really go here, resp. the NoGo-Flag
        ConditionalDistance oldSugg = suggestionTo1HopNeighbour;
        minDistsDirty();
        // hmm, was thought of as an optimization, but is almost equal, as the propagation would anyway stop soon
        if ( relEval != NOT_EVALUATED && evalIsOkForColByMin(relEval, opponentColor(color()), -EVAL_HALFAPAWN)) {
            setLatestChangeToNow();
            setKillable();
        }
        if (oldSugg==null
              //  || relEval == NOT_EVALUATED
                || !minDistanceSuggestionTo1HopNeighbour().cdEquals(oldSugg)
        ) {
            // if we cannot tell or suggestion has changed, trigger updates
            setLatestChangeToNow();
            if (oldSugg != null
                    && relEval != NOT_EVALUATED
                    &&  ( oldSugg.cdIsSmallerThan(minDistanceSuggestionTo1HopNeighbour())
                                     || oldSugg.cdIsEqualButDifferentSingleCondition(minDistanceSuggestionTo1HopNeighbour()) )
            ) {
                myPiece().quePropagation(
                        0,
                        this::propagateResetIfUSWToAllNeighbours);
            }
            quePropagateDistanceChangeToAllNeighbours();
        }

    }

    /**
     * initial set of contribution of myPiece at myPos.
     * to be called in updateClashResultAndRelEvals.
     * in later phaases use addClashContrib()!
     * @param relClashContrib the contribution...
     */
    public void setClashContrib(int relClashContrib) {
        this.relClashContrib = relClashContrib;
    }

    public void addClashContrib(int relClashContrib) {
        this.relClashContrib += relClashContrib;
    }

    public void setCheckGiving() {
        isCheckGiving = true;
    }

    public void setAbzugCheckGivingBy(VirtualPieceOnSquare checker) {
        abzugChecker = checker;
    }

    /**
     * future Level, where 0 is directly doable by the next move, of when my Piece can attack (or defend) the place here.
     * Thus, rawMinDistance is used here.
     * @return the fl+1 (1-MAX)
     */
    int getAttackingFutureLevelPlusOne() {
        ConditionalDistance rmd = getRawMinDistanceFromPiece();
        int inFutureLevel = rmd.dist()  // - 1 : TODO: take PlusOne from name and make this here return one less :-)  (but don't forget to adapt all the usages...)
                + rmd.countHelpNeededFromColorExceptOnPos(opponentColor(color()), this.myPos);
        if (inFutureLevel < 1)
            inFutureLevel = 1;
        return inFutureLevel;
    }

    /**
     * future Level, where 0 is directly doable by the next move, for a move of my Piece to here.
     * This implies that an own piece already standing here must move away first. Thus the minDistance is used.
     * @return the fl (0-MAX)
     */
    int getStdFutureLevel() {
        ConditionalDistance rmd = getMinDistanceFromPiece();
        int inFutureLevel = rmd.dist() - 1
                + rmd.countHelpNeededFromColorExceptOnPos(opponentColor(color()), this.myPos);
        if (inFutureLevel <= 0)
            inFutureLevel = 0;
        return inFutureLevel;
    }


    public void addMobility(int mobility) {
        this.mobilityFromHere += mobility;
    }


    public void addMobilityMap(int mobMap) {
        this.mobilityMapFromHere |= mobMap;
    }

    /**
     * gives out benefit for blocking the way of an attacker to here.
     * Called on the to-vPce that likes to move here (or further via here)
     *
     * @param attackFromPos
     * @param futureLevel
     * @param benefit if benefit==0 then do not give benefit, just count blockers
     * @return nr of immeditate (d==1) real blocks by opponent found.
     */
    int addBenefitToBlockers(final int attackFromPos, int futureLevel, final int benefit) {
        return addBenefitToBlockers(attackFromPos, futureLevel, benefit, getMyPos());
    }

    int addBenefitToBlockers(final int attackFromPos, int futureLevel, final int benefit, final int target) {
        if (futureLevel<0)  // TODO: may be deleted later, after stdFutureLevel is fixed to return one less (safely)
            futureLevel=0;
        int countBlockers = 0;
        // first find best blockers
        int closestDistInTimeWithoutNoGo = MAX_INTERESTING_NROF_HOPS+1;
        for (int pos : calcPositionsFromTo(attackFromPos, this.getMyPos() )) {
            if ( pos != attackFromPos
                    && pos != this.getMyPos()
                    && pos != board.getKingPos(myOpponentsColor())
                    && board.hasPieceOfColorAt(myOpponentsColor(), pos)
            ) {
                countBlockers++;  // already blocked? should this be possible in the call to this method? if yes, then this is a definitely one more blocker...
                if (DEBUGMSG_MOVEEVAL)
                    debugPrint(DEBUGMSG_MOVEEVAL, " strange count increase at pos=" + pos + ". ");
                //continue;
            }
            for (VirtualPieceOnSquare blocker : board.getBoardSquare(pos).getVPieces()) {
                // TODO!: do not operate on blocker, but loop over its LMOs and treat them separately
                // TODO-2: if this is done, this method can also serve to replace the addChance-loop too look for pieces(@lmos) covering the target square
                if ( !isAReasonableBlockerForMe(blocker)  // TODO: rethink this check, blocking a checkmate could also "unreasonably"(=looosing material) be ok!
                        || blocker.getMyPiecePos() == attackFromPos )  // otherwise blocker is not alive any more... note: we do not count, but we later give it a benefit, in case it moves first!
                continue;
                int blockerFutureLevel = blocker.getAttackingFutureLevelPlusOne() - 1;   // - (blocker.color()==board.getTurnCol() ? 1 : 0);
                boolean ineffectiveBlocker = false;
                if ( pos == attackFromPos || pos == this.getMyPos() ) {
                    if (board.getPieceIdAt(pos) == getPieceID() ) {
                        if (blockerFutureLevel > 0)
                            continue; // it also makes no sense to chase away the piece that wants to move anyway
                    }
                    else if (blocker.color() != color() && blockerFutureLevel == 0) {
                        if ( !( isPawn(blocker.getPieceType())
                                && ((VirtualPawnPieceOnSquare)blocker).lastPawnMoveIsStraight() )
                             && blocker.movetoHereIsNotBlockedByKingPin()
                        ) {
                            countBlockers++; // it is already blocking the hopping point (except if it is a straight moving pawn)
                            if (DEBUGMSG_MOVEEVAL)
                                debugPrint(DEBUGMSG_MOVEEVAL, " already blocking the hopping point: " + blocker + ": ");
                        }
                        continue; // it makes no sense to move opponent blocker in the way where it can directly be taken
                    }
                    else if (blocker.color() != color() && blockerFutureLevel > 0
                            && isPawn(blocker.getPieceType())
                            && ((VirtualPawnPieceOnSquare)blocker).lastPawnMoveIsStraight() ) {
                        continue; // a finally straight moving pawn cannot defend the square
                    }
                    blockerFutureLevel--;   // we are close to a turning point on the way of the attacker, it is sufficient to cover the square
                }
                else {
                    int attackDelta = board.getBoardSquare(pos).countDirectAttacksWithColor(blocker.color())
                            - board.getBoardSquare(pos).countDirectAttacksWithColor(blocker.myOpponentsColor());
                    if ( !evalIsOkForColByMin(blocker.getRelEvalOrZero(),blocker.color())
                        || ( abs(blocker.getRelEvalOrZero()) < EVAL_HALFAPAWN
                            && (attackDelta < 0    // still better or equal amount of covarage after defender moves there
                            || (attackDelta == 0 && isPawn(blocker.getPieceType()) ) ) ) )
                        ineffectiveBlocker = true;
                }

                if ( isPawn(blocker.getPieceType())  // a pawn cannot move here to block by taking (it would be blocked already)
                        && !((VirtualPawnPieceOnSquare)blocker).lastPawnMoveIsStraight()
                        && pos != getMyPos() )
                    continue;

                if (blockerFutureLevel<0)
                    blockerFutureLevel=0;
                int finalFutureLevel = futureLevel - blockerFutureLevel;
                if ( ( ( blocker.coverOrAttackDistance() == 1
                         && pos != getMyPos() )
                       || ( blocker.coverOrAttackDistance() == 2
                            && pos == getMyPos() ) )
                      //&& blocker.getMyPiecePos() != attackFromPos  // otherwise blocker is not alive anymore... note: we do not count, but we later give it a benefit, in case it moves first!
                ) {
                    if (!ineffectiveBlocker)
                        countBlockers++;
                    if (DEBUGMSG_MOVEEVAL)
                        debugPrintln(DEBUGMSG_MOVEEVAL, " found " + (ineffectiveBlocker?"in":"")
                                + "effective blocker " + blocker + ": ");
                }
                if ( finalFutureLevel >= 0
                        && blocker.getRawMinDistanceFromPiece().dist() < closestDistInTimeWithoutNoGo
                ) { // new closest blocker distance
                    closestDistInTimeWithoutNoGo = blocker.getRawMinDistanceFromPiece().dist();
                }
            }
        }
        if (benefit==0)
            return countBlockers;

        // give benefit
        if (DEBUGMSG_MOVEEVAL) {
            debugPrint(DEBUGMSG_MOVEEVAL, " motivate blockers from " + squareName(attackFromPos)
                    +" to "+ squareName(getMyPos())+": ");
        }
        for (int p : calcPositionsFromTo(attackFromPos, this.myPos)) {
            if ( p != attackFromPos
                    && p != this.getMyPos()
                    && p != board.getKingPos(myOpponentsColor())
                    && board.hasPieceOfColorAt(myOpponentsColor(), p)
            ) {
                // already blocked by piece here
                if (DEBUGMSG_MOVEEVAL)
                    debugPrint(DEBUGMSG_MOVEEVAL, " (reward=clashContrib of "+(benefit-(benefit >> 3))
                            + " for already blocking by "+board.getPieceAt(p)+") ");
                board.getBoardSquare(this.getMyPos())
                        .getvPiece( board.getBoardSquare(p).myPiece().getPieceID() )
                        .addClashContrib( benefit-(benefit >> 3) );
                //continue;
            }
            for (VirtualPieceOnSquare blocker : board.getBoardSquare(p).getVPieces()) {
                if (
// not in u50*board.hasPieceOfColorAt(myOpponentsColor(), p) ||
                        !isAReasonableBlockerForMe(blocker) )
                    continue;
                int finalBenefit = ( abs(blocker.getValue()) <= abs(getValue()) )
                        ? (benefit-(benefit >> 3))
                        : (benefit >> 2);
                if ( board.hasPieceOfColorAt(myOpponentsColor(), p)   // a piece of the opponent is already there and the blocker is also already covering it.
                        && p != board.getKingPos(myOpponentsColor())
                        && blocker.color() != color()
                        && blocker.getRawMinDistanceFromPiece().dist() == 1 ) {
                    // motivate blocker to remain covering the real (already there) blocker (it should anyway already have a clash contribution if it necessary to cover the piece here
                    if (DEBUGMSG_MOVEEVAL)
                        debugPrint(DEBUGMSG_MOVEEVAL, " (reward=clashContrib of "+(finalBenefit>>3)
                            + " for guarding already existing blocker "+board.getPieceAt(p)+" by: "+blocker+") ");
                    blocker.addClashContrib(finalBenefit>>3);
                    //continue;  //removed in u50
                    finalBenefit>>=1;
                }
                if ( blocker.getRawMinDistanceFromPiece().dist() > closestDistInTimeWithoutNoGo )
                    finalBenefit >>= 1; // others are closer  - it will be diminished more further down because of future level being big
                int blockerFutureLevel = blocker.getAttackingFutureLevelPlusOne() - 1; //- (blocker.color()==board.getTurnCol() ? 1 : 0);
                boolean ineffectiveBlocker = false;
                if ( p == attackFromPos  || p == this.getMyPos()) {
                    if (board.getPieceIdAt(p) == getPieceID() ) {
                        if (blockerFutureLevel > 0)
                            continue; // it also makes no sense to chase away the piece that wants to move anyway
                    }
                    else if (blocker.color() != color() && blockerFutureLevel == 0) {
                        if ( !( isPawn(blocker.getPieceType())
                                && ((VirtualPawnPieceOnSquare)blocker).lastPawnMoveIsStraight() )
                                && blocker.movetoHereIsNotBlockedByKingPin()
                        ) {
                            // give staying-bonus to blocker - it already blocks the turning point.
                            if (blocker.coverOrAttackDistance() == 1) {
                                if (DEBUGMSG_MOVEEVAL)
                                    debugPrint(DEBUGMSG_MOVEEVAL, " (reward=clashContrib of " + finalBenefit
                                            + " for guarding waypoint: " + blocker + ") ");
                                blocker.addClashContrib(finalBenefit);
                            }
                        }
                        continue; // but it makes no sense to move opponent blocker in the way where it can directly be taken
                    }
                    else if (blocker.color() != color() && blockerFutureLevel > 0
                            && isPawn(blocker.getPieceType()) && ((VirtualPawnPieceOnSquare)blocker).lastPawnMoveIsStraight() ) {
                        continue; // a finally straight moving pawn cannot defend the square
                    }
                    blockerFutureLevel--;   // we are close to a turning point on the way of the attacker, it is sufficient to cover the square
                }
                else {
                    int attackDelta = board.getBoardSquare(p).countDirectAttacksWithColor(blocker.color())
                                      - board.getBoardSquare(p).countDirectAttacksWithColor(blocker.myOpponentsColor());
                    if ( !evalIsOkForColByMin(blocker.getRelEvalOrZero(),blocker.color())
                            || ( abs(blocker.getRelEvalOrZero()) < EVAL_HALFAPAWN
                                 && (attackDelta < 0    // still better or equal amount of covarage after defender moves there
                                     || (attackDelta == 0 && !isPawn(blocker.getPieceType()) ) ) ) )  // only the pawn (must be straight move to an empty square) does not decrease the coverage by moving there
                        ineffectiveBlocker = true;
                }

                if ( isPawn(blocker.getPieceType())  // a pawn cannot move here to block by taking (it would be blocked already)
                        && !((VirtualPawnPieceOnSquare)blocker).lastPawnMoveIsStraight()
                        && p != getMyPos() ) {
                    continue;
                }

                if (blockerFutureLevel<0)
                    blockerFutureLevel=0;
                int finalFutureLevel = futureLevel - blockerFutureLevel;

                if (finalFutureLevel<0) { // coming too late
                    finalFutureLevel = -finalFutureLevel;
                    finalBenefit >>= 2 + (finalFutureLevel<<1);
                    if (DEBUGMSG_MOVEEVAL && abs(finalBenefit) > ( (p!=attackFromPos && blocker.getMinDistanceFromPiece().hasNoGo()) ? 16 : 4) )
                        debugPrint(DEBUGMSG_MOVEEVAL, " (too late but still:) ");
                }
                else if (finalFutureLevel>0) // still time
                    finalBenefit >>= finalFutureLevel;

                if ( blocker.getRawMinDistanceFromPiece().dist() > closestDistInTimeWithoutNoGo )
                    finalFutureLevel = max(futureLevel-1, blockerFutureLevel); // it is not one of the preferred clostest defenders, so lets calc fl differently

                if (p != attackFromPos && blocker.getMinDistanceFromPiece().hasNoGo())
                    finalBenefit >>= 3;   // a square "in between" must be safe to block.

                if (getRawMinDistanceFromPiece().needsHelpFrom(blocker.color()))
                    finalBenefit >>= 1;  // not so urgent to block, it is still blocked and opponent needs my help to unblock
                if (ineffectiveBlocker)
                    finalBenefit >>= 2;
                if (DEBUGMSG_MOVEEVAL && abs(finalBenefit) > DEBUGMSG_MOVEEVALTHRESHOLD)
                    debugPrint(DEBUGMSG_MOVEEVAL, " Benefit " + finalBenefit + "@" + finalFutureLevel
                            + " for " + (ineffectiveBlocker ? "ineffective ":"") + (futureLevel>0? "future ":"") + "blocking-move by " + blocker + " @" + blockerFutureLevel
                            + " to " + squareName(p)
                            + " against " + this + " @" + futureLevel + " coming from " + squareName(attackFromPos)+ ": ");

                //blocker.addRawChance(finalBenefit, finalFutureLevel, getMyPos()); // TODO!!: get target from caller
                // problem here, one cannot really say fl=0 or =defendFL, because it tries to block everything then, even future threats, that might not even come or be stoppable later anyway
                // but taking the original fl might seem to sloppy and one misses the chance to block future threats..., so try both?
                blocker.addRawChance(finalBenefit, futureLevel, target); //max(inFutureLevel, defendInFutureLevel));
                if (blockerFutureLevel<futureLevel) {
                    final int immediateBlockerBenefit = (finalBenefit >> 3) / (1 + futureLevel - blockerFutureLevel);
                    if (abs(immediateBlockerBenefit)>1)
                        blocker.addRawChance(immediateBlockerBenefit, blockerFutureLevel, target); //max(inFutureLevel, defendInFutureLevel));
                }
            }
        }
        return countBlockers;
    }

    private boolean isAReasonableBlockerForMe(VirtualPieceOnSquare blocker) {
        return blocker != null
                && blocker.color() == opponentColor(color())
                && blocker.getPieceID() != this.getPieceID()
                && !isKing(blocker.getPieceType())
                && blocker.getRawMinDistanceFromPiece().dist() < 3   //TODO?: make it generic for all future levels )
                && blocker.getRawMinDistanceFromPiece().dist() > 0
                && blocker.getRawMinDistanceFromPiece().isUnconditional()
                && !blocker.getRawMinDistanceFromPiece().hasNoGo()
                && blocker.movetoHereIsNotBlockedByKingPin();
    }

    /* 0.29z5 Discarded.
    unclear, why this "improved" and thought of as more precise version is clearly worse in the test games...
    try again:
    int BAD_addBenefitToBlockers(final int attackFromPos, int futureLevel, final int benefit) {
        if (futureLevel<0)  // TODO: may be deleted later, after stdFutureLevel is fixed to return one less (safely)
            futureLevel=0;
        int countBlockers = 0;
        // first find best blockers
        int closestDistinTimeWithoutNoGo = MAX_INTERESTING_NROF_HOPS+1;    // closest distance for a piece to be able to cover
        for (int pos : calcPositionsFromTo(attackFromPos, this.myPos)) {
            for (VirtualPieceOnSquare blocker : board.getBoardSquare(pos).getVPieces()) {
                if (blocker != null
                        && blocker.color() == opponentColor(color())
                        && blocker.getPieceID() != this.getPieceID()
                        && !isKing(blocker.getPieceType())
                        && blocker.getRawMinDistanceFromPiece().dist() < 4   //TODO?: make it generic for all future levels )
                        && blocker.getRawMinDistanceFromPiece().dist() > 0
                        && blocker.getRawMinDistanceFromPiece().isUnconditional()
                        && !blocker.getRawMinDistanceFromPiece().hasNoGo()
                ) {
                    int blockerFutureLevel = blocker.getStdFutureLevel();                     // - (blocker.color()==board.getTurnCol() ? 1 : 0);
                    if ( pos==attackFromPos && blocker.color() != board.getTurnCol() )
                        blockerFutureLevel--;   // we are close to a turning point on the way of the attacker, it is sufficient to cover the square
                    if (blockerFutureLevel<0)
                        blockerFutureLevel=0;
                    int finalFutureLevel = futureLevel-blockerFutureLevel+1;
                    if (blocker.getMinDistanceFromPiece().dist() < closestDistinTimeWithoutNoGo) { // new closest blocker, remember its distance
                        closestDistinTimeWithoutNoGo = blocker.getMinDistanceFromPiece().dist();
                    }
                }
            }
        }
        // give benefit
        for (Iterator<ChessPiece> it = board.getPiecesIterator(); it.hasNext(); ) {
            ChessPiece bPce = it.next();
            if (bPce==null)
                continue;
            int blockerBenefit = ( abs(bPce.getValue()) <= abs(myPiece().getValue()) )
                    ? (benefit-(benefit >> 3))
                    : (benefit >> 2);
            int maxBenefit = 0;
            int maxFL = 0;
            VirtualPieceOnSquare maxBlockerVPce = null;
            for (int p : calcPositionsFromTo(attackFromPos, this.myPos)) {
                VirtualPieceOnSquare blocker = board.getBoardSquare(p).getvPiece(bPce.getPieceID() );
                if (blocker != null
                        && blocker.color() == opponentColor(color())
                        && blocker.getPieceID() != this.getPieceID()
                        && !isKing(blocker.getPieceType())
                        && blocker.getRawMinDistanceFromPiece().dist() < 4   //TODO?: make it generic for all future levels )
                        && blocker.getRawMinDistanceFromPiece().dist() > 0
                        && blocker.getRawMinDistanceFromPiece().isUnconditional()
                        && !blocker.getRawMinDistanceFromPiece().hasNoGo()
                ) {
                    int finalBenefit = blockerBenefit;
                    int blockerFutureLevel = blocker.getStdFutureLevel();
                    if ( p==attackFromPos && blocker.color() != board.getTurnCol() ) {
                        // we are close to a turning point on the way of the attacker, it is sufficient to cover the square
                        blockerFutureLevel--;
                        // TODO: treat covering with more expensive pieces better - depend on real possible clash result there
                        if (blocker.getValue()-EVAL_TENTH > this.getValue())
                            finalBenefit >>= 2;
                    }
                    if (blockerFutureLevel<0)
                        blockerFutureLevel=0;
                    int finalFutureLevel = futureLevel-blockerFutureLevel+1;

                    if (finalFutureLevel<0) { // coming too late
                        finalBenefit /= 2 + blockerFutureLevel - futureLevel;
                        finalFutureLevel = blockerFutureLevel - futureLevel;
                    }
                    else if (finalFutureLevel>0) { // still time
                        finalBenefit -= finalBenefit>>2;  // *0.75
                        if (finalFutureLevel>1) // really still time :-)
                            finalBenefit = finalBenefit>>3 + (finalBenefit>>(finalFutureLevel-1));
                    }

                    if (blocker.getMinDistanceFromPiece().hasNoGo())
                        finalBenefit >>= 2;

                    if ( blocker.getMinDistanceFromPiece().dist() > closestDistinTimeWithoutNoGo+1 ) { // others are closer
                        finalBenefit /= blocker.getMinDistanceFromPiece().dist() - closestDistinTimeWithoutNoGo;
                    }

                    if ( isWhite(blocker.color()) && finalBenefit>maxBenefit
                         || isBlack(blocker.color()) && finalBenefit<maxBenefit
                    ) {  // remember best blocking square=vPce
                        if ( maxBlockerVPce==null  // count only one per piece
                                && blocker.getRawMinDistanceFromPiece().dist() == 1  && blocker.getRawMinDistanceFromPiece().isUnconditional())
                            countBlockers++;
                        maxBenefit = finalBenefit;
                        maxFL = finalFutureLevel;
                        maxBlockerVPce = blocker;
                    }
                    // reward all others also a little bit
                    finalBenefit >>= 2;  // 1/4
                    if (DEBUGMSG_MOVEEVAL && abs(finalBenefit) > 4)
                        debugPrint(DEBUGMSG_MOVEEVAL, " Benefit " + finalBenefit + "@" + finalFutureLevel
                                + " for blocking-move by " + blocker + " @" + blockerFutureLevel + " to " + squareName(p)
                                + " against " + this + " @" + futureLevel + " coming from " + squareName(attackFromPos)+ ": ");
                    //blocker.addRawChance(finalBenefit, finalFutureLevel);
                }
            }
            if (maxBlockerVPce != null) {
                maxBenefit -= maxBenefit>>2;  //  3/4  (a bot more than the rest of the above)
                if (DEBUGMSG_MOVEEVAL && abs(maxBenefit) > 4)
                    debugPrintln(DEBUGMSG_MOVEEVAL, " + Benefit " + maxBenefit + "@" + maxFL
                            + " for best blocking-move by " + maxBlockerVPce + " . ");
                maxBlockerVPce.addRawChance(maxBenefit, maxFL);
            }

        }

        return countBlockers;
    }
    */


    public int additionalChanceWouldGenerateForkingDanger(final int atPos, final int exceptPos, final int evalForTakingForkedOpponent) {
        if (rawMinDistance.dist()!=1) {
            // is at the moment only used and implemented at a dist==1
            //System.err.println("Error in additionalChanceHereWouldGenerateForkingDanger(): must be called for dist==1 only.");
            return 0;
        }
        final int futureLevel = 1;
        // run over all squares=vPces reachable from here, to see the max benefit with different move axis
        int maxChanceHere = 0;
        final int MIN_SIGNIFICANCE = EVAL_HALFAPAWN; // 50
        int attackDir = calcDirFromTo(getMyPiecePos(), getMyPos());
        if (DEBUGMSG_MOVEEVAL)
            debugPrint(DEBUGMSG_MOVEEVAL, " searching for fork benefits from " + this
                    + " towards "+squareName(atPos)+": ");
        for (VirtualPieceOnSquare nVPce : getAllNeighbours()) { // temp. backward-TEST, was already: getAllNeighbours()) {
            if (nVPce==null
                    || nVPce.getMyPos() == atPos  // leave out the one we like to fork
                    || nVPce.getMyPos() == exceptPos
                    || (attackDir != NONE && dirsAreOnSameAxis(attackDir, calcDirFromTo(getMyPos(), nVPce.getMyPos()) ) )
            )
                continue;
            if (DEBUGMSG_MOVEEVAL)
                debugPrint(DEBUGMSG_MOVEEVAL, " (searching for fork benefit at "+squareName(nVPce.getMyPos())+")");
            //if (dir == calcDirFromTo(myPos, nVPce.myPos) )
            //    continue;

            int chanceHere = nVPce.getRelEvalOrZero();  // because the chance there is more calculated to motivate to come closer. was: getChanceAtLevel(futureLevel); // was: getChanceAtLevelViaPos(futureLevel, myPos); todo?: Was this "via" actually necessary?
            if ( evalIsOkForColByMin(chanceHere, color(), -MIN_SIGNIFICANCE)
                    && isBetterThenFor(chanceHere, maxChanceHere, color() ) ) {
                maxChanceHere = chanceHere;
                if (DEBUGMSG_MOVEEVAL)
                    debugPrint(DEBUGMSG_MOVEEVAL, "="+chanceHere+"!   ");
            }
        }
        debugPrintln(DEBUGMSG_MOVEEVAL, "");
        return minFor(maxChanceHere, evalForTakingForkedOpponent, color());  // maybe even more precise would be to take max with 2nd best instead of min with best
    }


    /** checks whether this vPce cannot be attacked (from current d==2) by lower rated piece
     * @return boolean
     */
    public boolean isASavePlaceToStay() {
        if (!evalIsOkForColByMin(getRelEvalOrZero(),color()))
            return false; // cannot be there anyway
        // todo check, if piece is part of a clash there
        for (VirtualPieceOnSquare attacker : board.getBoardSquare(myPos).getVPieces()) {
            if (attacker == null
                    || attacker.color()==color()
                    || attacker.getMinDistanceFromPiece().dist()!=2
                    || attacker.getMinDistanceFromPiece().hasNoGo()
                    || !attacker.getMinDistanceFromPiece().isUnconditional()
                    || abs(attacker.getValue()) > abs(getValue()) )
                continue;
            return false;
        }
        return true;
    }

    public int getClosestLastMoveOriginInDir(int dir) {
        int closestDist = max(NR_FILES,NR_RANKS)+1;  // who know, we might have an asymmetric board some day :-)
        int closestLmoPos = NOWHERE;
        for (VirtualPieceOnSquare lmo : this.getRawMinDistanceFromPiece().getLastMoveOrigins() ) {
            if ( calcDirFromTo( lmo.myPos, this.myPos) == dir) {
                int d = distanceBetween( lmo.myPos, this.myPos);
                if ( d < closestDist ) {
                    closestLmoPos = lmo.myPos;
                    closestDist = d;
                }
            }
        }
        return closestLmoPos;
    }

    boolean movetoHereIsNotBlockedByKingPin() {
        return board.moveIsNotBlockedByKingPin(myPiece(), getMyPos());
    }

    int coverOrAttackDistanceNogofree() {
        return coverOrAttackDistance(true);
    }

    int coverOrAttackDistance() {
        return coverOrAttackDistance(false);
    }

    int coverOrAttackDistance(boolean nogoIsInfinite ) {
        ConditionalDistance rmd = getRawMinDistanceFromPiece();
        int dist = rmd.dist();
        final int colorlessPieceType = colorlessPieceType(getPieceType());
        if (    // there must not be a NoGo on the way to get here  -  except for pawns, which currently signal a NoGo if they cannot "beat" to an empty square, but still cover it...
                ( nogoIsInfinite
                        && rmd.hasNoGo()
                        && (colorlessPieceType!=PAWN || rmd.getNoGo()!= getMyPos()) )  //TODo!: is a bug, if another nogo on the way was overritten - as only the last nogo is stored at he moment.
                || rmd.isInfinite()
                || dist>MAX_INTERESTING_NROF_HOPS ) {
            return INFINITE_DISTANCE;
        }
        //TODO: Check why this worsens the mateIn1-Test-Puzzles (from 223 to 291)
        //still we leave it here, it should improve other cases where being pinned needs to be obeyed
        if ( dist != 0 && !board.moveIsNotBlockedByKingPin(myPiece(), getMyPos()) ) {
            //debugPrintln(DEBUGMSG_MOVEEVAL,"King pin matters on square " + squareName(getMyPos()));
            if ( board.nrOfLegalMovesForPieceOnPos(board.getKingPos(color())) > 0 )  // todo: be more precise and check move axis
                dist++;  // if piece is pinned to king, increase dist by one, so first king can move away.
            else
                dist += 2; // even worse
        }
        if ( colorlessPieceType==PAWN ) {
            // some more exception for pawns
            if (// a pawn at dist==1 can beat, but not "run into" a piece
                //Todo: other dists are also not possible if the last step must be straight - this is hard to tell here
                    fileOf(getMyPos())==fileOf(myPiece().getPos()) && (dist==1 || (dist-rmd.nrOfConditions())==1)
            ) {
                return INFINITE_DISTANCE;
            }
            //in the same way do only count with extra distance, if it requires the opponent to do us a favour by
            // moving a piece to beat in the way (except the final piece that we want to evaluate the attacks/covers on)
            return dist + rmd.countHelpNeededFromColorExceptOnPos(opponentColor(color()), getMyPos());
        }
        else if ( dist==0 && colorlessPieceType==KING ) {
            // king is treated as if it'd defend itself, because after approach of the enemy, it has to move away and thus defends this square
            // TODO: Handle "King has no" moves here?
            return 2; //v0.29z4 tried 1 instead of 2, but even a little worse
        }

        // if distance is unconditional, then there is nothing more to think about:
        if (rmd.isUnconditional())
            return dist;

        // correct?: seems strange...
        //// other than that: same color coverage is always counted
        //if (myPieceID!=NO_PIECE_ID && vPce.color()==myPiece().color())
        //    return dist;
        //// from here on it is true: assert(colorlessPieceType(vPce.getPieceType())!=colorlessPieceType(myPieceType())
        //return dist;


        // a dist==1 + condition means a pinned piece is in between, but actually no direct attack
        // so we cannot count that piece as covering or attacking directly,
        if (dist==1 && rmd.nrOfConditions()>0)
            return 2;
        else
            return dist;  // else simply take the dist... same color conditions are counted as 1 dist anyway and opponent ones... who knows what will happen here

        // this case has become very simple :-)
        // it used to be that: add opponents if they are in beating distance already or of different piece type (as
        //      the same piece type can usually not come closer without being beaten itself
        // but: now this should already be covered by the NoGo mechanism.
        // was:  if ( colorlessPieceType(vPce.getPieceType())==colorlessPieceType(myPieceType())
        //             && rmd.dist()==1 )
        // TODO: check+testcases if this working (even for the formerly complex cases: "but do not add queen or king on hv-dirs with dist>2 if I have a rook
        //        //       and also not a queen or king on diagonal dirs with dit <2 if I have a bishop"
        // TODO - to be implemented not here, but in the nogo-code!: check if piece here is king-pinned or costly to move away then opponent could perhaps come closer anyway

        //??         && ( ! (colorlessPieceTypeNr(vPce.getPieceType())==QUEEN   // similarly a queen cannot attack a rook or a bishop  if it is the attack direction with the same distance backwards
        //                   && myChessBoard.getBoardSquares()[vPce.myPos].getvPiece(myPieceID).rawMinDistance.dist()==vPce.getMinDistanceFromPiece().getShortestDistanceEvenUnderCondition() ) ) ) )

    }


    public int getMyPos() {
        return myPos;
    }

    /**
     * only works for attacks to real pieces (not vPces with dist>0), so needs to be called on square with target piece
     * @return whether the piece here can reasonably beat back while I approach it.
     * TODO!!!!: Account only(!) for possible approachig ways (lmos) that do not have NoGo.
     * Be aware, will remain false, if that beating back had a condition.
     */
    boolean attackTowardsPosMayFallVictimToSelfDefence() {
        Square toSq = board.getBoardSquare(getMyPos());
        return this.getRawMinDistanceFromPiece().getLastMoveOrigins().stream()           // for all lmos from where to reach the pinned piece
                .map(vPce -> Integer.valueOf(board.getBoardSquare(vPce.getMyPos())       // take the distance of them from the pinned piece
                        .getvPiece(toSq.getPieceID())
                        .coverOrAttackDistance()))
                .mapToInt(v -> v)
                .max()
                .orElse(0) == 1;
    }

    /**
     * like attackTowardsPosMayFallVictimToSelfDefence, but only looking at moves in the direction towards
     * here via viaPos (e.g. for pin scenarios, where the approaching direction is significant).
     * Called at the final target (e.g. the king to which the attacked piece is pinned)
     * @param viaPos
     * @return whether the piece here can reasonably beat back while I approach it via viaPos. Be aware, will remain false, if
     * that beating back had a condition.
     */
    boolean attackViaPosTowardsHereMayFallVictimToSelfDefence(int viaPos) {
        Square toSq = board.getBoardSquare(getMyPos());
        return toSq.getvPiece(getPieceID()).getRawMinDistanceFromPiece().getLastMoveOrigins().stream()           // all lmos from where to reach the pinned piece
                .filter(vPce -> isBetweenFromAndTo(viaPos, vPce.getMyPos(), getMyPos()))
                .filter(vPce -> !vPce.getMinDistanceFromPiece().hasNoGo() )
                .map(vPce -> Integer.valueOf(board.getBoardSquare(vPce.getMyPos())       // take the distance of them from the pinned piece
                        .getvPiece(toSq.getPieceID())
                        .coverOrAttackDistance()))
                .mapToInt(v -> v)
                .max()
                .orElse(0) == 1;
    }

    void resetPredecessors() {
        shortestReasonableUnconditionedPredecessors = null;
        firstMovesWithReasonableShortestWayToHere = null;
    }

    /** looks if a particular fromPos is straight above this position.
     * So, this only compares the frompos with the current pos.
     * It does not look at all last moves to here.
     * @param fromPos
     * @return true is this is a pawn, that here is on the same file as fromPos and is moving, i.e. not the myPiecePos.
     */
    boolean isStraightMovingPawn(final int fromPos) {
        return false;
   }

    public void resetRelEvalsAndChances() {
        this.relEval = NOT_EVALUATED;
        //not setRelEval(NOT_EVALUATED); because this triggers dist updates due to relEval-change
        resetKillable();
        resetBasics();
        resetPredecessors();
    }

    boolean isSuitableAdditionalAttacker() {
        return getRawMinDistanceFromPiece().dist() < 2    // skip if it is already part of the more attacks, because it is close
                && !(getRawMinDistanceFromPiece().dist() == 1
                && getRawMinDistanceFromPiece().hasExactlyOneFromToAnywhereCondition())
                || getMinDistanceFromPiece().hasNoGo();   // or if it has a NoGo on the way
    }
}


