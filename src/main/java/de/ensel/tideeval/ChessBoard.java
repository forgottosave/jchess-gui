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
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static de.ensel.tideeval.ChessBasics.*;
import static de.ensel.tideeval.EvaluatedMove.addEvaluatedMoveToSortedListOfCol;
import static de.ensel.tideeval.Move.getMoves;
import static java.lang.Math.*;
import static java.text.MessageFormat.*;

public class ChessBoard {

    /**
     * configure here which debug messages should be printed
     * (not final, to be able to change it during debugging)
     */
    public static boolean DEBUGMSG_DISTANCE_PROPAGATION = false;
    public static boolean DEBUGMSG_CLASH_CALCULATION = false;
    public static boolean DEBUGMSG_CBM_ERRORS = false;
    public static boolean DEBUGMSG_TESTCASES = false;
    public static boolean DEBUGMSG_BOARD_INIT = false;
    public static boolean DEBUGMSG_FUTURE_CLASHES = false;
    public static boolean DEBUGMSG_MOVEEVAL = false;   // <-- best for checking why moves are evaluated the way they are
    public static int DEBUGMSG_MOVEEVALTHRESHOLD = 5;   // no report below this benefit
    public static boolean DEBUGMSG_MOVEEVAL_AGGREGATION = false;
    public static boolean DEBUGMSG_MOVEEVAL_INTEGRITY = false;
    public static boolean DEBUGMSG_MOVEEVAL_COMPARISON = false;
    public static boolean DEBUGMSG_MOVESELECTION = false || DEBUGMSG_MOVEEVAL;
    public static boolean DEBUGMSG_MOVESELECTION2 = false || DEBUGMSG_MOVESELECTION;
    public static boolean DEBUGMSG_DISTANCE_REPETITION = false || DEBUGMSG_DISTANCE_PROPAGATION || DEBUGMSG_MOVESELECTION2;

    // controls the debug messages for the verification method of creating and comparing each board's properties
    // with a freshly created board (after each move)
    public static final boolean DEBUGMSG_BOARD_COMPARE_FRESHBOARD = false;  // full output
    public static final boolean DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL = false || DEBUGMSG_BOARD_COMPARE_FRESHBOARD;  // output only verification problems

    public static boolean DEBUGMSG_BOARD_MOVES = false || DEBUGMSG_BOARD_COMPARE_FRESHBOARD;

    //const automatically activates the additional creation and compare with a freshly created board
    // do not change here, only via the DEBUGMSG_* above.
    public static final boolean DEBUG_BOARD_COMPARE_FRESHBOARD = DEBUGMSG_BOARD_COMPARE_FRESHBOARD || DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL;

    public static int DEBUGFOCUS_SQ = coordinateString2Pos("e1");   // changeable globally, just for debug output and breakpoints+watches
    public static int DEBUGFOCUS_VP = 0;   // changeable globally, just for debug output and breakpoints+watches
    private final ChessBoard board = this;       // only exists to make naming in debug evaluations easier (unified across all classes)

    private long boardHash;
    private List<List<Long>> boardHashHistory;  // 2 for the colors - then ArrayList<>(50) for 50 Hash values:

    private int whiteKingPos;
    private int blackKingPos;

    private int currentDistanceCalcLimit;
    private int[][] nrOfKingAreaAttacks = new int[2][2];    // nr of direct (inkl. 2nd row) attacks to [king of colorindex] by [piece of colorindex]
 //   private int[] nrOfFutureKingAreaAttackDangers = new int[2];    // nr of future attacks to [king of colorindex]

    public static int MAX_INTERESTING_NROF_HOPS = 6; // sufficient for pawns to see their future as a nice queen :-)
    private int[] nrOfLegalMoves = new int[2];
    protected EvaluatedMove bestMove;

    //private int[] kingChecks  = new int[2];
    private boolean gameOver;

    private int repetitions;

    private Square[] boardSquares;
    String fenPosAndMoves;

    private static int engineP1 = 0;  // engine option - used at varying places for optimization purposes.

    /**
     * keep all Pieces on Board
     */
    ChessPiece[] piecesOnBoard;
    private int nextFreePceID;
    public static final int NO_PIECE_ID = -1;

    private int countOfWhitePieces;  // todo: make array with colorindex
    private int countOfBlackPieces;
    private int[] countBishops = new int[2];  // count bishops for colorIndex
    private int[] countKnights = new int[2];  // count knights for colorIndex
    private int[][] countPawnsInFile = new int[2][NR_FILES];  // count pawns in particular file for colorIndex



    /**
     * Constructor
     * for a fresh ChessBoard in Starting-Position
     */
    public ChessBoard() {
        initChessBoard(new StringBuffer(chessBasicRes.getString("chessboard.initalName")), FENPOS_STARTPOS);
    }

    public ChessBoard(String boardName) {
        initChessBoard(new StringBuffer(boardName), FENPOS_STARTPOS);
    }

    public ChessBoard(String boardName, String fenBoard) {
        initChessBoard(new StringBuffer(boardName), fenBoard);
        if (fenBoard != FENPOS_STARTPOS)   // sic. string-pointer compare ok+wanted here
            debugPrintln(DEBUGMSG_BOARD_INIT, "with [" + fenBoard + "] ");
    }

    public static int engineP1() {
        return engineP1;
    }

    public static void setEngineP1(int i) {
        engineP1 = i;
    }

    private void initChessBoard(StringBuffer boardName, String fenBoard) {
        if (DEBUGMSG_BOARD_INIT) {
            debugPrintln(DEBUGMSG_BOARD_INIT, "");
            debugPrint(DEBUGMSG_BOARD_INIT, "New Board " + boardName + ": ");
        }
        this.boardName = boardName;
        setCurrentDistanceCalcLimit(0);
        updateBoardFromFEN(fenBoard);
        calcBestMove();
    }


    private void emptyBoard() {
        piecesOnBoard = new ChessPiece[MAX_PIECES];
        countOfWhitePieces = 0;
        countOfBlackPieces = 0;
        for (int ci=0; ci<=1; ci++) {
            countBishops[ci] = 0;
            countKnights[ci] = 0;
            for (int f=0; f<NR_FILES; f++)
                countPawnsInFile[ci][f] = 0;
        }
        nextFreePceID = 0;
        boardSquares = new Square[NR_SQUARES];
        for (int p = 0; p < NR_SQUARES; p++) {
            boardSquares[p] = new Square(this, p);
        }
    }

    private void setDefaultBoardState() {
        countBoringMoves = 0;
        kingsideCastlingAllowed[CIWHITE] = false;  /// s.o.
        queensideCastlingAllowed[CIWHITE] = false;
        kingsideCastlingAllowed[CIBLACK] = false;
        queensideCastlingAllowed[CIBLACK] = false;
        enPassantFile = -1;    // -1 = not possible,   0 to 7 = possible to beat pawn of opponent on col A-H
        turn = WHITE;
        fullMoves = 0;
        whiteKingPos = NOWHERE;
        blackKingPos = NOWHERE;
        resetHashHistory();
    }

    public ChessPiece getPiece(int pceID) {
        assert (pceID < nextFreePceID);
        return piecesOnBoard[pceID];
    }

    /**
     * give ChessPiece at pos
     *
     * @param pos board position
     * @return returns ChessPiece or null if quare is empty
     */
    ChessPiece getPieceAt(int pos) {
        int pceID = getPieceIdAt(pos);
        if (pceID == NO_PIECE_ID)
            return null;
        return piecesOnBoard[pceID];
    }

    public int getPieceIdAt(int pos) {
        return boardSquares[pos].getPieceID();
    }

    boolean hasPieceOfColorAt(boolean col, int pos) {
        if (boardSquares[pos].getPieceID() == NO_PIECE_ID || getPieceAt(pos) == null)   // Todo-Option:  use assert(getPiecePos!=null)
            return false;
        return (getPieceAt(pos).color() == col);
    }

    public int distanceToKing(int pos, boolean kingCol) {
        if (kingCol == WHITE)
            return distanceBetween(pos, whiteKingPos);
        else
            return distanceBetween(pos, blackKingPos);
    }

    public boolean isCheck(boolean col) {
        return nrOfChecks(col) > 0;
    }

    public int nrOfChecks(boolean col) {
        int kingPos = getKingPos(col);
        if (kingPos < 0)
            return 0;  // king does not exist... should not happen, but is part of some test-positions
        Square kingSquare = getBoardSquare(kingPos);
        return kingSquare.countDirectAttacksWithout2ndRowWithColor(opponentColor(col));
    }

    /////
    ///// the Chess Game as such
    /////

    private void checkAndEvaluateGameOver() {    // called to check+evaluate if there are no more moves left or 50-rules-move is violated
        if (countOfWhitePieces <= 0 || countOfBlackPieces <= 0) {
            gameOver = true;
        } else if (isCheck(getTurnCol()) && nrOfLegalMoves(getTurnCol()) == 0) {
            gameOver = true;
        } else
            gameOver = false;
    }

    private static final String[] evalLabels = {
            "game state",
            "piece values",
            "basic mobility",
            "max.clashes",
            "new mobility",
            "attacks on opponent side",
            "attacks on opponent king",
            "defends on own king",
            "Mix Eval",
            "pceVals + best move[0]",
            "pceVals + best move[0]+[1]/4"
            //, "2xmobility + max.clash"
    };

    protected static final int EVAL_INSIGHT_LEVELS = evalLabels.length;


    static String getEvaluationLevelLabel(int level) {
        return evalLabels[level];
    }

    // [EVAL_INSIGHT_LEVELS];

    /**
     * calculates board evaluation according to several "insight levels"
     *
     * @param levelOfInsight: 1 - sum of plain standard figure values,
     *                        2 - take piece position into account
     *                        -1 - take best algorithm currently implemented
     * @return board evaluation in centipawns (+ for white, - for an advantage of black)
     */
    public int boardEvaluation(int levelOfInsight) {
        if (levelOfInsight >= EVAL_INSIGHT_LEVELS || levelOfInsight < 0)
            levelOfInsight = EVAL_INSIGHT_LEVELS - 1;
        int[] eval = new int[EVAL_INSIGHT_LEVELS];
        // first check if its over...
        checkAndEvaluateGameOver();
        if (isGameOver()) {                         // gameOver
            if (isCheck(WHITE))
                eval[0] = WHITE_IS_CHECKMATE;
            else if (isCheck(BLACK))
                eval[0] = BLACK_IS_CHECKMATE;
            eval[0] = 0;
        } else if (isWhite(getTurnCol()))       // game is running
            eval[0] = +1;
        else
            eval[0] = -1;
        if (levelOfInsight == 0)
            return eval[0];
        // even for gameOver we try to calculate the other evaluations "as if"
        int l = 0;
        eval[++l] = evaluateAllPiecesBasicValueSum(); /*1*/
        if (levelOfInsight == l)
            return eval[l];
        eval[++l] = evaluateAllPiecesBasicMobility();
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = evaluateMaxClashes();
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = evaluateAllPiecesMobility();
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = evaluateOpponentSideAttack();
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = evaluateOpponentKingAreaAttack();
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = evaluateOwnKingAreaDefense();
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = (int) (eval[3] * 1.2) + eval[4] + eval[5] + eval[6] + eval[7];
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = getBestEvaluatedMove() != null ? (getBestEvaluatedMove()).getEvalAt(0)/10 : 0;
        if (levelOfInsight == l)
            return eval[1] + eval[l];
        eval[++l] = eval[l-1] + ( getBestEvaluatedMove() != null ? ( (getBestEvaluatedMove()).getEvalAt(1)/32) : 0);
        if (levelOfInsight == l)
            return eval[1] + eval[l];

        // hier one should not be able to end up, according to the parameter restriction/correction at the beginning
        // - but javac does not see it like that...
        assert (false);
        return 0;
    }

    public int boardEvaluation() {
        // for a game that has ended, the official evaluation is in level 0 (so that the others remain available "as if")
        if (isGameOver())
            return boardEvaluation(0);
        return boardEvaluation(EVAL_INSIGHT_LEVELS - 1);
    }

    private int evaluateAllPiecesBasicValueSum() {
        /*error: return getPiecesStream()
                .filter(Objects::nonNull)
                .mapToInt(pce -> pce.getBaseValue() )
                .sum(); */
        //or old fashioned :-)
        int pceValSum = 0;
        for (ChessPiece pce : piecesOnBoard)
            if (pce != null)
                pceValSum += pce.baseValue();
        return pceValSum;

    }

    // idea: could become an adapdable parameter later
    private static int EVALPARAM_CP_PER_MOBILITYSQUARE = 4;

    private int evaluateAllPiecesBasicMobility() {
        // this is not using streams, but a loop, as the return-type int[] is to complex to "just sum up"
        int[] mobSumPerHops = new int[MAX_INTERESTING_NROF_HOPS];
        // init mobility sum per hop
        for (int i = 0; i < MAX_INTERESTING_NROF_HOPS; i++)
            mobSumPerHops[i] = 0;
        for (ChessPiece pce : piecesOnBoard) {
            if (pce != null) {
                int[] pceMobPerHops = pce.getSimpleMobilities();
                //add this pieces mobility per hop to overall the sub per hop
                if (isWhite(pce.color()))
                    for (int i = 0; i < MAX_INTERESTING_NROF_HOPS; i++)
                        mobSumPerHops[i] += pceMobPerHops[i] * EVALPARAM_CP_PER_MOBILITYSQUARE;
                else  // count black as negative
                    for (int i = 0; i < MAX_INTERESTING_NROF_HOPS; i++)
                        mobSumPerHops[i] -= pceMobPerHops[i] * EVALPARAM_CP_PER_MOBILITYSQUARE;
            }
        }
        // sum first three levels up into one value, but weight later hops lesser
        int mobSum = mobSumPerHops[0];
        for (int i = 1; i < Math.min(3, MAX_INTERESTING_NROF_HOPS); i++)  // MAX_INTERESTING_NROF_HOPS
            mobSum += mobSumPerHops[i] >> (i + 1);   // rightshift, so hops==2 counts quater, hops==3 counts only eightth...
        return (int) (mobSum * 0.9);
    }

    private int evaluateAllPiecesMobility() {
        // this is not using streams, but a loop, as the return-type int[] is to complex to "just sum up"
        int mobSum = 0;
        // init mobility sum per hop
        for (ChessPiece pce : piecesOnBoard) {
            if (pce != null) {
                //add this pieces mobility to overall sum
                if (pce.isWhite())
                    mobSum += pce.getMobilities() * EVALPARAM_CP_PER_MOBILITYSQUARE;
                else
                    mobSum -= pce.getMobilities() * EVALPARAM_CP_PER_MOBILITYSQUARE;
            }
        }
        return (int) (mobSum);
    }


    /* sum of clashes brings no benefit:
    of board evaluations: 17421
    Quality of level clash sum (3):  (same as basic piece value: 6769)
     - improvements: 5687 (-112)
     - totally wrong: 4167 (164); - overdone: 798 (142)
    private int evaluateSumOfDirectClashResultsOnOccupiedSquares() {
        int clashSumOnHopLevel1 = 0;
        for (ChessPiece p: piecesOnBoard) {
            if (p==null)
                continue;
            Square s = boardSquares[p.getPos()];
            clashSumOnHopLevel1 += s.clashEval(1);
        }
        return clashSumOnHopLevel1;
    } */

    int evaluateMaxClashes() {
        int clashMaxWhite = Integer.MIN_VALUE;
        int clashMinBlack = Integer.MAX_VALUE;
        for (ChessPiece p : piecesOnBoard) {
            if (p == null)
                continue;
            int clashResult = boardSquares[p.getPos()].clashEval();
            if (p.isWhite()) {
                clashMinBlack = min(clashMinBlack, clashResult);
            } else {
                clashMaxWhite = max(clashMaxWhite, clashResult);
            }
        }
        // for a first simple analysis we do not look at any dependencies of the clashes.
        // assumption: the color whose turn it is can either win at least the best clash
        // or hinder the opponent from its best clash (we calc it as reduction to 1/16th)
        // after that, the opponent does the same - but this for now is counted only half...
        if (DEBUGMSG_CLASH_CALCULATION)
            debugPrintln(DEBUGMSG_CLASH_CALCULATION, String.format(" w: %d  b: %d ", clashMaxWhite, clashMinBlack));
        if (isWhite(getTurnCol())) {
            if (clashMaxWhite > -clashMinBlack)
                return (clashMaxWhite > Integer.MIN_VALUE ? clashMaxWhite : 0)
                        + (clashMinBlack < Integer.MAX_VALUE ? clashMinBlack / 2 : 0);
            return (clashMaxWhite > Integer.MIN_VALUE ? clashMaxWhite / 4 : 0); // + clashMinBlack/8;
        }
        // else blacks turn
        if (clashMaxWhite < -clashMinBlack)
            return (clashMaxWhite > Integer.MIN_VALUE ? clashMaxWhite / 2 : 0)
                    + (clashMinBlack < Integer.MAX_VALUE ? clashMinBlack : 0);
        return (clashMinBlack < Integer.MAX_VALUE ? clashMinBlack / 4 : 0); // + clashMaxWhite/8;
    }

    int evaluateOpponentSideAttack() {
        int pos;
        int sum = 0;
        for (pos = 0; pos < NR_FILES * 3; pos++)
            sum += boardSquares[pos].getAttacksValueForColor(WHITE) * (rankOf(pos) >= 6 ? 2 : 1);
        for (pos = NR_SQUARES - NR_FILES * 3; pos < NR_SQUARES; pos++)
            sum -= boardSquares[pos].getAttacksValueForColor(BLACK) * (rankOf(pos) <= 1 ? 2 : 1);
        return sum;
    }

    int evaluateOpponentKingAreaAttack() {
        int pos;
        int[] sum = {0, 0, 0, 0};
        for (pos = 0; pos < NR_SQUARES; pos++) {
            int dbk = distanceToKing(pos, BLACK);
            int dwk = distanceToKing(pos, WHITE);
            if (dbk < 4)
                sum[dbk] += boardSquares[pos].getAttacksValueForColor(WHITE);
            if (dwk < 4)
                sum[dwk] -= boardSquares[pos].getAttacksValueForColor(BLACK);
        }
        return sum[1] * 2 + sum[2] + sum[3] / 3;
    }

    int evaluateOwnKingAreaDefense() {
        int pos;
        int[] sum = {0, 0, 0, 0};
        for (pos = 0; pos < NR_SQUARES; pos++) {
            int dbk = distanceToKing(pos, BLACK);
            int dwk = distanceToKing(pos, WHITE);
            if (dbk <= 3)
                sum[dbk] -= boardSquares[pos].getAttacksValueForColor(BLACK);
            if (dwk <= 3)
                sum[dwk] += boardSquares[pos].getAttacksValueForColor(WHITE);
        }
        return sum[1] + sum[2] + sum[3] / 4;
    }


    /**
     * triggers distance calculation for all pieces, stepwise up to toLimit
     * this eventually does the breadth distance propagation
     *
     * @param toLimit final value of currentDistanceCalcLimit.
     */
    private void continueDistanceCalcUpTo(int toLimit) {
        debugPrintln(DEBUGMSG_DISTANCE_REPETITION, "---" );
        debugPrintln(DEBUGMSG_DISTANCE_REPETITION, "Distance calculation for board: " + getBoardFEN() );

        for (int currentLimit = 1; currentLimit <= toLimit; currentLimit++) {
            setCurrentDistanceCalcLimit(currentLimit);
            nextUpdateClockTick();
            /*int processed;
            int emergencyBreak = 0;
            do {
                processed = 0;
             */
                for (ChessPiece pce : piecesOnBoard)
                    if (pce != null)
                        pce.continueDistanceCalc();
                        /*if ( pce.continueDistanceCalc() )
                            processed++; */
                nextUpdateClockTick();
            /*    emergencyBreak++;
            } while (processed>0 && emergencyBreak<=3);
            if (DEBUGMSG_DISTANCE_REPETITION) {
                debugPrint(DEBUGMSG_DISTANCE_REPETITION, (currentLimit==1? ("Nr of repetitions (toLimit "+ toLimit + "): ") : ", ") + emergencyBreak + (processed>0 ? ("-"+processed) : "") );
                if (processed > 0)
                    internalErrorPrintln("Nr of Update Rounds not sufficient.");
                if (currentLimit==toLimit)
                    debugPrintln(DEBUGMSG_DISTANCE_REPETITION, ". ");
            } */
            // update calc, of who can go where safely
            for (Square sq : boardSquares)
                sq.updateClashResultAndRelEvals();

            if (currentLimit == 2) {
                markCheckBlockingSquares();
            }
            if (currentLimit == 2) {
                // collect legal moves
                for (ChessPiece p : piecesOnBoard)
                    if (p != null) {
                        p.collectUnevaluatedMoves();
                    }
            }
            else if (currentLimit == 3 || currentLimit==6) {
                for (ChessPiece pce : piecesOnBoard)
                    if (pce != null)
                        pce.prepareMoves( currentLimit == MAX_INTERESTING_NROF_HOPS );
            }
        }

    }


    private void evalBeingTrappedOptions(ChessPiece pce) {
        EvaluatedMove[] bestMoveOnAxis = pce.getBestReasonableEvaluatedMoveOnAxis();
        // do I have a good move away?
        int nrOfAxisWithReasonableMoves = 0;
        if (colorlessPieceType(pce.getPieceType())==KNIGHT)
            nrOfAxisWithReasonableMoves = pce.getLegalMovesAndChances().size();
        else
            for (int i=0; i<4; i++)
                if (bestMoveOnAxis[i]!=null)
                    nrOfAxisWithReasonableMoves++;
        if ( nrOfAxisWithReasonableMoves > 1         // many moves on at least two axis
                || ( pce.color() != board.getTurnCol()   // I could just positively take the piece, I do not need to trap it!
                    && !evalIsOkForColByMin( board.getBoardSquare(pce.getPos()).clashEval(), pce.color(), -EVAL_HALFAPAWN ) ) )
            return;
        // iterate ovar all enemies that can attack me soon
        for (VirtualPieceOnSquare attacker : board.getBoardSquare(pce.getPos()).getVPieces()) {
            if (attacker == null
                    || attacker.color() == pce.color()
                    || attacker.coverOrAttackDistance()<=1  // already attacking
                    || ( isPawn(attacker.getPieceType()) && attacker.coverOrAttackDistance()!=2)  // for now no traps by pawns except directly possible, as long rang for pawns works only badly
            ) {
                continue;
            }
            ConditionalDistance aRmd = attacker.getRawMinDistanceFromPiece();
            final int fullBenefit = isKing(pce.getPieceType()) ? (-pieceBaseValue(pce.getPieceType())>>3)
                    : attacker.getRelEval();
            if ( fullBenefit == NOT_EVALUATED
                    || abs(fullBenefit) > (checkmateEval(BLACK) << 2)
                    || !evalIsOkForColByMin(fullBenefit, attacker.color(), -EVAL_TENTH )
                    || !aRmd.distIsNormal()
                    || ( aRmd.dist() + aRmd.countHelpNeededFromColorExceptOnPos(pce.color(), pce.getPos())
                         >= MAX_INTERESTING_NROF_HOPS )
            ) {
                continue;
            }

            /*for debugging only:
            if ( (!attacker.getDirectAttackVPcs().containsAll(attacker.getShortestReasonableUnconditionedPredecessors())
                    || !attacker.getShortestReasonableUnconditionedPredecessors().containsAll(attacker.getDirectAttackVPcs()))
                    && !isPawn(attacker.getPieceType()))
                board.internalErrorPrintln("predecessor-deviation for attacker " + attacker + ": "
                        + attacker.getShortestReasonableUnconditionedPredecessors() + ", "
                        + attacker.getDirectAttackVPcs() + ".");
            */

            // iterate over positions from where the attacker can come to here
            // works best by far: attacker.getShortestReasonableUnconditionedPredecessors()
            // bad: .getDirectAttackVPcs() and also .getShortestReasonablePredecessorsAndDirectAttackVPcs() ) {
            Set<VirtualPieceOnSquare> attacksFrom;
            //if (isPawn(attacker.getPieceType()))
                attacksFrom = attacker.getDirectAttackVPcs();
            //else
            //    attacksFrom = attacker.getShortestReasonableUncondPredAndDirectAttackVPcs();
            for ( VirtualPieceOnSquare attackerAtAttackingPosition : attacksFrom ) {
                if ( attackerAtAttackingPosition.getShortestReasonablePredecessorsAndDirectAttackVPcs().contains(attacker))
                    continue; // attacking position is "behind" the position we want to attack =it has a shortest predecessor via the pce we like tp trap, so it is unrealistic that we would attack the pce from there
                ConditionalDistance aAPosRmd = attackerAtAttackingPosition.getRawMinDistanceFromPiece();
                int inFutureLevel = attackerAtAttackingPosition.getStdFutureLevel(); // not: + (aAPosRmd.isUnconditional() ? 0 : 1);
                int attackDir = calcDirFromTo(attackerAtAttackingPosition.getMyPos(), pce.getPos());
                int benefit = fullBenefit - (fullBenefit >> 2);  // *0.75 - start out a bit lower, it is never sure if these traps work...

                boolean noRealTrap = !( // if not any of the following trapping conditions:
                        nrOfAxisWithReasonableMoves == 0          // no move
                        || (nrOfAxisWithReasonableMoves == 1   // or only move axis is along the attack axis...
                            && isSlidingPieceType(attacker.getPieceType())
                            && bestMoveOnAxis[convertDir2AxisIndex(attackDir)] != null)
                      )
                      // or trapped piece cannot be reasonably approached by attacker
                      /*TC: || attacker.attackViaPosTowardsHereMayFallVictimToSelfDefence(attackerAtAttackingPosition.getMyPos())
                      not following: */
                      || (colorlessPieceType(attacker.getPieceType()) == colorlessPieceType(pce.getPieceType()))  // same type cannot attack with benefit.
                      || (colorlessPieceType(attacker.getPieceType()) == QUEEN
                            && (colorlessPieceType(pce.getPieceType()) == ROOK && isRookDir(attackDir)) // Queen cannot attack rook from straight
                            && (colorlessPieceType(pce.getPieceType()) == BISHOP && isBishopDir(attackDir)) // Queen cannot attack bishop from diagonal
                         )
                      || !aAPosRmd.distIsNormal()
                      || inFutureLevel > 4;


                if (DEBUGMSG_MOVEEVAL)
                    debugPrintln(DEBUGMSG_MOVEEVAL, (noRealTrap ? "Not a real t" : "T") +"rapping candidate for " + pce
                            + " with moves on " + nrOfAxisWithReasonableMoves + " axis, by attacker at " + attackerAtAttackingPosition
                            + " to " + squareName(pce.getPos())
                            + "(dir=" + attackDir
                            + " =>axisIndex=" + (attackDir == NONE ? "x" : convertDir2AxisIndex(calcDirFromTo(attackerAtAttackingPosition.getMyPos(), pce.getPos()))) + ").");

                if (noRealTrap)
                    continue;  // no benefit if not really trapped


                int countBlockers = attacker.addBenefitToBlockers(attackerAtAttackingPosition.getMyPos(),
                        inFutureLevel, 0);

                //TODO: Count + substract freeing positions, like in checkmate testing! otherwise attacker might "trap", but let go at the same time!

                if (isKing(pce.getPieceType()) && countBlockers == 0) {
                    // could be mate, cannot even  move something in between -> make it more urgent to everyone!
                    if (DEBUGMSG_MOVEEVAL)
                        debugPrintln(DEBUGMSG_MOVEEVAL, " King trapping = being mated danger detected of " + benefit + "@" + inFutureLevel + " for " + attackerAtAttackingPosition + ".");
                    // NOT here, as long as the above todo for freeing positions is not implemented! benefit = checkmateEval(pce.color());
                    benefit <<= 1;
                    // there are no blockers, but still the method is called for future blockers:
                    attacker.addBenefitToBlockers(attackerAtAttackingPosition.myPos, inFutureLevel, -benefit>>1);
                }
                else if (countBlockers > 0) {
                    benefit /= 2 + countBlockers;
                    if (inFutureLevel>=2)  // getting trapped is still quite far away, traps are probably not long lived
                        benefit = (benefit>>3) + (benefit>>(inFutureLevel-1));
                }

                // TODO:hasNoGo is not identical to will reasonably survive a path, e.g. exchange with same
                //  piecetype cuold be 0, so it is not nogo, but the piece will be gone still...
                if (aAPosRmd.hasNoGo())
                    benefit >>= 3;
                else if (attackerAtAttackingPosition.getMinDistanceFromPiece().hasNoGo()) {
                    // threat is immanent, but still blocked at the last moment
                    //TC instead of benefit >>= 1:
                    if (aAPosRmd.dist() == 1
                            && aAPosRmd.isUnconditional()
                    ) {
                        benefit >>= 1;
                        //benefit -= benefit >> 2;  // *0.75 almost full threat, that needs to be avoided
                        // let's tell my pieces who cover here, to better stay and block this square
                        // old comment "solve problem here: This almost never works", trying again
                        // as most of the times the relevant Square has NoGo and is often not even part of the predecessors (which prefer way without nogo, although longer)
                        if (DEBUGMSG_MOVEEVAL)
                            debugPrint(DEBUGMSG_MOVEEVAL, " Contributions for already covering the trapping position " + attackerAtAttackingPosition + ": ");
                        board.getBoardSquare(attackerAtAttackingPosition.myPos)
                                .contribToDefendersByColor(-benefit,pce.color());
                        /*for (VirtualPieceOnSquare coveringVPce : board.getBoardSquare(attackerAtAttackingPosition.myPos).getVPieces()) {
                            if (coveringVPce != null && coveringVPce.color() == pce.color()) {
                                ConditionalDistance coveringRmd = coveringVPce.getRawMinDistanceFromPiece();
                                if ( coveringVPce.coverOrAttackDistance() != 1
                                        || !coveringRmd.isUnconditional() ) {
                                    continue;
                                }
                                if (DEBUGMSG_MOVEEVAL)
                                    debugPrintln(DEBUGMSG_MOVEEVAL, " Contribution for currently covering a trapping position: " + benefit + "@" + inFutureLevel + " for " + coveringVPce + ".");
                                coveringVPce.addClashContrib(-benefit);
                            }
                        }*/
                    }
                    else
                        benefit >>= 3;
                }
                if ( !attackerAtAttackingPosition.getMinDistanceFromPiece().isUnconditional()
                          || !aRmd.isUnconditional() ) {
                    benefit >>= 2;
                }

                // now award blockers
                attacker.addBenefitToBlockers(attackerAtAttackingPosition.getMyPos(),
                        inFutureLevel, -benefit);

                // fee for enabling a trap by moving out of the way
                if ( aAPosRmd.dist() == 1
                     && ( aAPosRmd.hasExactlyOneFromToAnywhereCondition()  // a condition at the first of two moves
                          || ( aAPosRmd.isUnconditional() && aRmd.hasExactlyOneFromToAnywhereCondition()) )  // or at the second (taking the trapped piece) move
                ) {
                    int fromPos = NOWHERE;
                    if (aRmd.hasExactlyOneFromToAnywhereCondition())
                        fromPos = aRmd.getFromCond(0);
                    else
                        fromPos = aAPosRmd.getFromCond(0);
                    if (fromPos >= 0 ) {
                        ChessPiece p = board.getPieceAt(fromPos);
                        if (p != null) {
                            //if this piece moves away, the trap will become doable...
                            if (DEBUGMSG_MOVEEVAL)
                                debugPrintln(DEBUGMSG_MOVEEVAL, "Move away fee of " + benefit + "@" + (0) + " to " + p + " to stay and block trap of " + attacker + ".");
                            p.addMoveAwayChance2AllMovesUnlessToBetween(-benefit,0,
                                    attackerAtAttackingPosition.getMyPos(), attacker.getMyPos(), false, attacker.getMyPos());
                        }
                    }
                }

                // seemed to make sense, but test games are a significantly worse - see 47u32(with) vs. 0.47u33(without)
                /*
                if ( abs(attacker.getValue()) > abs(pce.getValue() )
                        && board.getBoardSquare(pce.getPos()).countFutureAttacksWithColor(pce.color(),2)>0
                )
                    benefit >>= 1;  // if the attacker is more expensive than the trapped piece, covering the piece is (partly) a solution
                */
                if (DEBUGMSG_MOVEEVAL && abs(benefit) > DEBUGMSG_MOVEEVALTHRESHOLD)
                    debugPrintln(DEBUGMSG_MOVEEVAL, " Trapping benefit of " + benefit + "@" + inFutureLevel + " for " + attackerAtAttackingPosition + ".");
                attackerAtAttackingPosition.addChance(benefit, inFutureLevel, pce.getPos() );
            }
        }
    }

    private void calcCheckBlockingBenefitsFor(boolean col) {
        int kingPos = isWhite(col) ? whiteKingPos : blackKingPos;
        if (kingPos>=0)   // except for testboards without king
            boardSquares[kingPos].calcCheckBlockingOptions();
    }

    private void setCheckingsFor(boolean col) {
        int kingPos = isWhite(col) ? whiteKingPos : blackKingPos;
        if (kingPos>=0)   // except for testboards without king
            boardSquares[kingPos].setCheckings();
    }

    /**
     * counts and stores the nr of direct (inkl. 2nd row) attacks and defences to king of color col
     * @param col
     */
    private void countKingAreaAttacks(boolean col) {  //TODO!!: should count attackers not attacks!
        int kingPos = getKingPos(col);
        if (kingPos<0)   // must be a testboard without king
            return;
        int ci = colorIndex(col);
        Arrays.fill( nrOfKingAreaAttacks[ci], 0);
        for ( VirtualPieceOnSquare neighbour : boardSquares[kingPos].getvPiece(getKingId(col)).getNeighbours() ) {
            Square nSq = boardSquares[neighbour.getMyPos()];
            nrOfKingAreaAttacks[ci][CIWHITE] += nSq.countDirectAttacksWithColor(WHITE)
                + (nSq.extraCoverageOfKingPinnedPiece(WHITE) ? 1 : 0);
            nrOfKingAreaAttacks[ci][CIBLACK] += nSq.countDirectAttacksWithColor(BLACK)
                + (nSq.extraCoverageOfKingPinnedPiece(BLACK) ? 1 : 0);
            nSq.markKingAreaAttackersWithColor(col);
        }
    }

    private void markCheckBlockingSquares() {
        for (Square sq : getBoardSquares())
            sq.resetBlocksChecks();
        for (int ci = 0; ci <= 1; ci++) { // for both colors
            boolean col = colorFromColorIndex(ci);
            int kingpos = getKingPos(col);
            if (kingpos < 0)
                continue;          // in some test-cases boards without kings are used, so skip this (instead of error/abort)
            List<ChessPiece> attackers = getBoardSquare(kingpos).directAttackersWithout2ndRowWithColor(opponentColor(col));
            for (ChessPiece a : attackers) {
                for (int pos : a.allPosOnWayTo(kingpos))
                    boardSquares[pos].setBlocksCheckFor(col);
            }
        }
    }

    /**
     * triggers all open distance calculation for all pieces
     */
    void completeCalc() {
        resetBestMoves();

        continueDistanceCalcUpTo(MAX_INTERESTING_NROF_HOPS);

        for (ChessPiece pce : piecesOnBoard)
            if (pce!=null) {
                pce.preparePredecessors();
                pce.evaluateMobility();
                pce.rewardMovingOutOfTrouble();
                pce.resetKingAreaAttacker();
            }
        countKingAreaAttacks(WHITE);
        countKingAreaAttacks(BLACK);
        setCheckingsFor(WHITE);
        setCheckingsFor(BLACK);
        calcCheckBlockingBenefitsFor(WHITE);
        calcCheckBlockingBenefitsFor(BLACK);

        for (ChessPiece pce : piecesOnBoard)
            if (pce!=null)
                pce.reduceToSingleContribution();
        for (Square sq : boardSquares) {
            sq.calcFutureClashEval();
        }
        for (Square sq : boardSquares) {
            sq.calcExtraBenefits();
        }
        for (ChessPiece pce : piecesOnBoard)
            if (pce!=null) {
                evalBeingTrappedOptions(pce);
                // re-replaces by old method from .46u21, so for now no more: pce.giveLuftForKingInFutureBenefit();
            }
        for (Square sq : boardSquares) {
            sq.evalCheckingForks();
        }
        for (Square sq : boardSquares) {
            sq.evalContribBlocking();
        }
        for (Square sq : boardSquares) {
            sq.avoidForks();
            sq.avoidRunningIntoForks();
        }
        motivateToEnableCastling(WHITE);
        motivateToEnableCastling(BLACK);
    }

    private void motivateToEnableCastling(boolean col) {
        if ( !isKingsideCastleAllowed(col) || isKingsideCastlingPossible(col) )
            return;
        int rookPos = board.findRook(getKingPos(col)+1, isWhite(col) ? coordinateString2Pos("h1") : coordinateString2Pos("h8"));
        if ( rookPos <= getKingPos(col) )
            return;
        for (int pos : calcPositionsFromTo(getKingPos(col)+1, rookPos)) {
            getBoardSquare(pos).motivateToEnableCastling(col);
        }
    }

    private void resetBestMoves() {
        bestMove = null;
        for (ChessPiece pce : piecesOnBoard)
            if (pce != null) {
                pce.resetBestMoves();
                pce.resetRelEvalsAndChances();
                pce.resetChancesOfAllVPces();
            }
    }


    //boolean accessibleForKing(int pos, boolean myColor);
    //boolean coveredByMe(int pos, boolean color);
    //boolean coveredByMeExceptOne(int pos, boolean color, int pieceNr);

    //Piece getPieceOnSquare(int pos);
    //int getPieceNrOnSquare(int pos);

    private boolean turn;
    private StringBuffer boardName;

    /**
     * Get (simple) fen string from the current board
     *
     * @return String in FEN notation representing the current board and game status
     */
    String getBoardFEN() {
        StringBuilder fenString = new StringBuilder();
        for (int rank = 0; rank < NR_RANKS; rank++) {
            if (rank > 0)
                fenString.append("/");
            int spaceCounter = 0;
            for (int file = 0; file < NR_FILES; file++) {
                int pceType = getPieceTypeAt(rank * 8 + file);
                if (pceType == EMPTY) {
                    spaceCounter++;
                } else {
                    if (spaceCounter > 0) {
                        fenString.append(spaceCounter);
                        spaceCounter = 0;
                    }
                    fenString.append(fenCharFromPceType(pceType));
                }
            }
            if (spaceCounter > 0) {
                fenString.append(spaceCounter);
            }
        }
        return fenString + "" + getFENBoardPostfix();
    }
    //StringBuffer[] getBoard8StringsFromPieces();


    String getFENBoardPostfix() {
        return (turn == WHITE ? " w " : " b ")
                + (isKingsideCastleAllowed(WHITE) ? "K" : "") + (isQueensideCastleAllowed(WHITE) ? "Q" : "")
                + (isKingsideCastleAllowed(BLACK) ? "k" : "") + (isQueensideCastleAllowed(BLACK) ? "q" : "")
                + ((!isKingsideCastleAllowed(WHITE) && !isQueensideCastleAllowed(WHITE)
                && !isKingsideCastleAllowed(BLACK) && !isQueensideCastleAllowed(BLACK)) ? "- " : " ")
                + (getEnPassantFile() == -1 ? "- " : (Character.toString(getEnPassantFile() + 'a') + (turn == WHITE ? "6" : "3")) + " ")
                + countBoringMoves
                + " " + fullMoves;
    }


    /**
     * create a new Piece on the board
     *
     * @param pceType type of white or black piece - according to type constants in ChessBasics
     * @param pos     square position on board, where to spawn that piece
     * @return returns pieceID of the new Piece
     */
    int spawnPieceAt(final int pceType, final int pos) {
        final int newPceID = nextFreePceID++;
        assert (nextFreePceID <= MAX_PIECES);
        assert (pos >= 0 && pos < NR_SQUARES);
        if (isPieceTypeWhite(pceType)) {
            countOfWhitePieces++;
            if (pceType == KING)
                whiteKingPos = pos;
        } else {
            countOfBlackPieces++;
            if (pceType == KING_BLACK)
                blackKingPos = pos;
        }

        switch (colorlessPieceType(pceType)) {
            case BISHOP -> countBishops[colorIndexOfPieceType(pceType)]++;
            case KNIGHT -> countKnights[colorIndexOfPieceType(pceType)]++;
            case PAWN   -> countPawnsInFile[colorIndexOfPieceType(pceType)][fileOf(pos)]++;
        }

        piecesOnBoard[newPceID] = new ChessPiece(this, pceType, newPceID, pos);
        // tell all squares about this new piece
        for (Square sq : boardSquares)
            sq.prepareNewPiece(newPceID);

        // construct net of neighbours for this new piece
        for (int p = 0; p < NR_SQUARES; p++) {
            switch (colorlessPieceType(pceType)) {
                case ROOK -> carefullyEstablishSlidingNeighbourship4PieceID(newPceID, p, HV_DIRS);
                case BISHOP -> {
                    if (isSameSquareColor(pos, p)) // only if square  has same square color than the bishop is standing on
                        carefullyEstablishSlidingNeighbourship4PieceID(newPceID, p, DIAG_DIRS); //TODO: leave out squares with wrong color for bishop
                }
                case QUEEN -> carefullyEstablishSlidingNeighbourship4PieceID(newPceID, p, ROYAL_DIRS);
                case KING -> carefullyEstablishSingleNeighbourship4PieceID(newPceID, p, ROYAL_DIRS);
                case KNIGHT -> carefullyEstablishKnightNeighbourship4PieceID(newPceID, p, KNIGHT_DIRS);
                case PAWN -> {
                    if (piecesOnBoard[newPceID].pawnCanTheoreticallyReach(p))
                        carefullyEstablishSingleNeighbourship4PieceID(newPceID, p, getAllPawnDirs(colorOfPieceType(pceType), rankOf(p)));
                }
                default -> internalErrorPrintln(chessBasicRes.getString("errormessage.notImplemented"));
            }
        }
        // finally, add the new piece at its place
        boardSquares[pos].spawnPiece(newPceID);
        //updateHash
        return newPceID;
    }

    /*private void establishSingleNeighbourship4PieceID(int pid, int pos, int neighboursDir) {
        boardSquares[pos].getvPiece(pid).addSingleNeighbour(boardSquares[pos+neighboursDir].getvPiece(pid));
    }*/

    private void carefullyEstablishSlidingNeighbourship4PieceID(int pid, int pos, int[] neighbourDirs) {
        VirtualPieceOnSquare vPiece = boardSquares[pos].getvPiece(pid);
        Arrays.stream(neighbourDirs)
                .filter(d -> neighbourSquareExistsInDirFromPos(d, pos))    // be careful at the borders
                .forEach(d -> vPiece.addSlidingNeighbour(boardSquares[pos + d].getvPiece(pid), d));
    }

    private void carefullyEstablishSingleNeighbourship4PieceID(int pid, int pos, int[] neighbourDirs) {
        VirtualPieceOnSquare vPiece = boardSquares[pos].getvPiece(pid);
        Arrays.stream(neighbourDirs)
                .filter(d -> neighbourSquareExistsInDirFromPos(d, pos))    // be careful at the borders
                .forEach(d -> vPiece.addSingleNeighbour(boardSquares[pos + d].getvPiece(pid)));
    }

    private void carefullyEstablishKnightNeighbourship4PieceID(int pid, int pos, int[] neighbourDirs) {
        VirtualPieceOnSquare vPiece = boardSquares[pos].getvPiece(pid);
        Arrays.stream(neighbourDirs)
                .filter(d -> knightMoveInDirFromPosStaysOnBoard(d, pos))    // be careful at the borders
                .forEach(d -> vPiece.addSingleNeighbour(boardSquares[pos + d].getvPiece(pid)));
    }

    public void removePiece(int pceID) {
        piecesOnBoard[pceID] = null;
        for (Square sq : boardSquares)
            sq.removePiece(pceID);
    }

    protected boolean updateBoardFromFEN(String fenString) {
        if (fenString == null || fenString.length() == 0)
            fenString = FENPOS_STARTPOS;
        Move[] movesToDo = null;
        boolean changed = true;
        if (fenPosAndMoves != null
                && fenString.startsWith(fenPosAndMoves)) {
            if (fenString.equals(fenPosAndMoves))
                changed = false; // it seems we are called with the same fenString again!
            movesToDo = getMoves(fenString.substring(fenPosAndMoves.length()));
        } else {
            // it seems the fenString is a later position of my current position or a totally different one
            movesToDo = initBoardFromFEN(fenString);
        }
        if (movesToDo != null) {
            for (int i = 0; i < movesToDo.length; i++) {
                completeCalc();
                if (!doMove(movesToDo[i])) {
                    System.err.println("Error in fenstring moves: invalid move " + movesToDo[i] + " on " + this.getBoardFEN() + "");
                    // try manually
                    basicMoveFromTo(movesToDo[i].from(), movesToDo[i].to());
                }
            }
        }
        if (!fenString.equalsIgnoreCase(fenPosAndMoves)) {
            //System.err.println("Inconsistency in fen string: " + fenPosAndMoves
            //        + " instead of " + fenString);
            // still we continue...
        }
        fenPosAndMoves = fenString;
        completeCalc();
        return changed;
    }

    /**
     * inits empty chessboard with pieces and parameters from a FEN string
     *
     * @param fenString FEN String according to Standard with board and game attributes
     * @return returns list of (still open) moves that were appended to the fen string
     */
    private Move[] initBoardFromFEN(String fenString) {
        //fenPosAndMoves = fenString;
        setDefaultBoardState();
        emptyBoard();
        int figNr;
        int i = 0;
        int rank = 0;
        int file = 0;
        int pos = 0;
        while (i < fenString.length() && rank < 8) {
            int emptyfields = 0;
            switch (fenString.charAt(i)) {
                case '*', 'p', '♟' -> figNr = PAWN_BLACK;
                case 'o', 'P', '♙' -> figNr = PAWN;
                case 'L', 'B', '♗' -> figNr = BISHOP;
                case 'l', 'b', '♝' -> figNr = BISHOP_BLACK;
                case 'T', 'R', '♖' -> figNr = ROOK;
                case 't', 'r', '♜' -> figNr = ROOK_BLACK;
                case 'S', 'N', '♘' -> figNr = KNIGHT;
                case 's', 'n', '♞' -> figNr = KNIGHT_BLACK;
                case 'K', '♔' -> figNr = KING;
                case 'k', '♚' -> figNr = KING_BLACK;
                case 'D', 'Q', '♕' -> figNr = QUEEN;
                case 'd', 'q', '♛' -> figNr = QUEEN_BLACK;
                case '/' -> {
                    if (file != 8)
                        internalErrorPrintln("**** Inkorrekte Felder pro Zeile gefunden beim Parsen an Position " + i + " des FEN-Strings " + fenString);
                    pos += 8 - file; // statt pos++, um ggf. den Input-Fehler zu korrigieren
                    file = 0;
                    i++;
                    rank++;
                    continue;
                }
                case ' ', '_' -> {  // signals end of board in fen notation, but we also want to accept it as empty field
                    if (fenString.charAt(i) == ' ' && file == 8 && rank == 7) {
                        i++;
                        rank++;
                        pos++;
                        continue;
                    }
                    figNr = EMPTY;
                    emptyfields = 1;
                }
                default -> {
                    figNr = EMPTY;
                    if (fenString.charAt(i) >= '1' && fenString.charAt(i) <= '8')
                        emptyfields = fenString.charAt(i) - '1' + 1;
                    else {
                        internalErrorPrintln("**** Fehler beim Parsen an Position " + i + " des FEN-Strings " + fenString);
                    }
                }
            }
            if (figNr != EMPTY) {
                spawnPieceAt(figNr, pos);
                file++;
                pos++;
            } else {
                //spawn nothing
                pos += emptyfields;
                file += emptyfields;
                emptyfields = 0;
            }
            if (file > 8)
                System.err.println("**** Überlange Zeile gefunden beim Parsen an Position " + i + " des FEN-Strings " + fenString);
            // kann nicht vorkommen if (rank>8)
            //    System.err.println("**** Zu viele Zeilen gefunden beim Parsen an Position "+i+" des FEN-Strings "+fenString);
            i++;
        }
        // set board params from fen appendix
        // TODO: implementation is quite old, should use split etc...
        while (i < fenString.length() && fenString.charAt(i) == ' ')
            i++;
        Move[] postMoves = null;
        if (i < fenString.length()) {
            if (fenString.charAt(i) == 'w' || fenString.charAt(i) == 'W')
                turn = WHITE;
            else if (fenString.charAt(i) == 'b' || fenString.charAt(i) == 'B')
                turn = BLACK;
            else
                System.err.println("**** Fehler beim Parsen der Spieler-Angabe an Position " + i + " des FEN-Strings " + fenString);
            i++;
            while (i < fenString.length() && fenString.charAt(i) == ' ')
                i++;
            // castle indicators
            int nextSeperator = i;
            while (nextSeperator < fenString.length() && fenString.charAt(nextSeperator) != ' ')
                nextSeperator++;
            String whiteKcastleSymbols = ".*[" + ( (char)((int) 'A' + fileOf(whiteKingPos)) ) + "-H].*";
            String whiteQcastleSymbols = ".*[A-" + ( (char)((int) 'A' + fileOf(whiteKingPos)) ) + "].*";
            String blackKcastleSymbols = ".*[" + ( (char)((int) 'a' + fileOf(blackKingPos)) ) + "-h].*";
            String blackQcastleSymbols = ".*[a-" + ( (char)((int) 'a' + fileOf(blackKingPos)) ) + "].*";
            String castleIndicators = fenString.substring(i, nextSeperator);
            queensideCastlingAllowed[CIBLACK] = castleIndicators.contains("q") || ( castleIndicators.matches(blackQcastleSymbols));
            kingsideCastlingAllowed[CIBLACK] = castleIndicators.contains("k") || ( castleIndicators.matches(blackKcastleSymbols));
            queensideCastlingAllowed[CIWHITE] = castleIndicators.contains("Q") || ( castleIndicators.matches(whiteQcastleSymbols));
            kingsideCastlingAllowed[CIWHITE] = castleIndicators.contains("K") || ( castleIndicators.matches(whiteKcastleSymbols));
            // enPassant
            i = nextSeperator;
            while (i < fenString.length() && fenString.charAt(i) == ' ')
                i++;
            nextSeperator = i;
            while (nextSeperator < fenString.length() && fenString.charAt(nextSeperator) != ' ')
                nextSeperator++;
            if (fenString.substring(i, nextSeperator).matches("[a-h]([1-8]?)"))
                enPassantFile = fenString.charAt(i) - 'a';
            else {
                enPassantFile = -1;
                if (fenString.charAt(i) != '-')
                    System.err.println("**** Fehler beim Parsen der enPassant-Spalte an Position " + i + " des FEN-Strings " + fenString);
            }
            // halfMoveClock
            i = nextSeperator;
            while (i < fenString.length() && fenString.charAt(i) == ' ')
                i++;
            nextSeperator = i;
            while (nextSeperator < fenString.length() && fenString.charAt(nextSeperator) != ' ')
                nextSeperator++;
            if (fenString.substring(i, nextSeperator).matches("[0-9]+"))
                countBoringMoves = Integer.parseInt(fenString.substring(i, nextSeperator));
            else {
                countBoringMoves = 0;
                System.err.println("**** Fehler beim Parsen der halfMoveClock an Position " + i + " des FEN-Strings " + fenString);
            }
            // nr of full moves
            i = nextSeperator;
            while (i < fenString.length() && fenString.charAt(i) == ' ')
                i++;
            nextSeperator = i;
            while (nextSeperator < fenString.length() && fenString.charAt(nextSeperator) != ' ')
                nextSeperator++;
            if (fenString.substring(i, nextSeperator).matches("[0-9]+"))
                fullMoves = Integer.parseInt(fenString.substring(i, nextSeperator));
            else {
                fullMoves = 1;
            }

            // parse+collect appending move strings
            i = nextSeperator;
            fenPosAndMoves = fenString.substring(0, i);
            while (i < fenString.length() && fenString.charAt(i) == ' ')
                i++;
            postMoves = getMoves(fenString.substring(i));
        }
        initHash();
        // else no further board parameters available, stay with defaults
        return postMoves;
    }

    ///// Hash
    //private long boardFigureHash;
    //long getBoardHash();
    //long getBoardAfterMoveHash(int frompos, int topos);


    ///// MOVES

    protected boolean[] kingsideCastlingAllowed = new boolean[2];
    protected boolean[] queensideCastlingAllowed = new boolean[2];
    protected int enPassantFile;   // -1 = not possible,   0 to 7 = possible to beat pawn of opponent on col A-H

    public boolean isKingsideCastleAllowed(boolean col) {
        return kingsideCastlingAllowed[colorIndex(col)];
    }

    public boolean isQueensideCastleAllowed(boolean col) {
        return queensideCastlingAllowed[colorIndex(col)];
    }

    public int getEnPassantFile() {
        return enPassantFile;
    }

    public int getEnPassantPosForTurnColor(boolean color) {
        if (enPassantFile == -1)
            return -1;
        return fileRank2Pos(enPassantFile, isWhite(color) ? 5 : 2);
    }

    int countBoringMoves;
    int fullMoves;

    public int getCountBoringMoves() {
        return countBoringMoves;
    }

    public int getFullMoves() {
        return fullMoves;
    }

    /**
     * does calcBestMove when necessary (incl. checkAndEvaluateGameOver())
     * @return a string describing a hopefully good move (format "a1b2")
     */
    public String getMove() {
        Move m = getBestMove();
        if (m == null || !m.isMove())
            return "-";
        else
            return ((Move)m).toString();
    }

    /**
     * does calcBestMove() when necessary (incl. checkAndEvaluateGameOver())
     * @return a hopefully good Move
     */
    public Move getBestMove() {
        if (bestMove==null)
            calcBestMove();
        if (bestMove==null)
            return null;
        return new Move(bestMove);
    }


    private EvaluatedMove getBestEvaluatedMove() {
        if (bestMove==null)
            calcBestMove();
        return bestMove;
    }


    /**
     * the actual calculation... includes checkAndEvaluateGameOver()
     */
    private void calcBestMove() {
        final int lowest = (getTurnCol() ? WHITE_IS_CHECKMATE : BLACK_IS_CHECKMATE);
        int[] bestEvalSoFar = new int[MAX_INTERESTING_NROF_HOPS + 1];
        int[] bestOpponentEval = new int[MAX_INTERESTING_NROF_HOPS + 1];
        Arrays.fill(bestEvalSoFar, lowest);
        Arrays.fill(bestOpponentEval, -lowest);
        Arrays.fill(nrOfLegalMoves, 0);
        // collect chances for moves
        for (ChessPiece p : piecesOnBoard)
            if (p != null)
                p.aggregateVPcesChancesAndCollectMoves();

        // map chances of moves to lost or prolonged chances for the same piece's other moves
        for (ChessPiece p : piecesOnBoard)
            if (p != null)
                p.mapLostChances();
        // first for opponent, then for "me"
        for (ChessPiece p : piecesOnBoard)
            if (p != null && p.color() != getTurnCol())
                nrOfLegalMoves[colorIndex(opponentColor(getTurnCol()))] += p.selectBestMove();
        for (ChessPiece p : piecesOnBoard)
            if (p != null && p.color() == getTurnCol())
                nrOfLegalMoves[colorIndex(getTurnCol())] += p.selectBestMove();

        // Compare all moves returned by all my pieces and find the best.
        List<EvaluatedMove> bestOpponentMoves = getBestMoveForColWhileAvoiding( opponentColor(getTurnCol()), null);
        List<EvaluatedMove> bestMovesSoFar    = getBestMoveForColWhileAvoiding( getTurnCol(), bestOpponentMoves);
        if (DEBUGMSG_MOVESELECTION) {
            debugPrintln(DEBUGMSG_MOVESELECTION, "=> My best move: "+ bestMovesSoFar+".");
            debugPrintln(DEBUGMSG_MOVESELECTION, "(opponents best moves: " + bestOpponentMoves + ").");
        }
        bestMove = bestMovesSoFar.size()>0 ?bestMovesSoFar.get(0) : null;
        checkAndEvaluateGameOver();
    }

    private List<EvaluatedMove> getBestMoveForColWhileAvoiding(final boolean col, final List<EvaluatedMove> bestOpponentMoves) {
        final int maxBestMoves = col==getTurnCol() ? 5 : 20;
        List<EvaluatedMove> bestMoves = new ArrayList<>(maxBestMoves);
        List<EvaluatedMove> restMoves = new ArrayList<>(maxBestMoves);
        nrOfLegalMoves[colorIndex(col)] = 0;
        for (ChessPiece p : piecesOnBoard) {
            if (p != null && p.color() == col) {
                for (EvaluatedMove pEvMove : p.getBestEvaluatedMoves()) {
                    EvaluatedMove reevaluatedPEvMove = reevaluateMove(col, bestOpponentMoves, p, pEvMove);
                    if (reevaluatedPEvMove == null)
                        continue;
                    if (DEBUGMSG_MOVESELECTION)
                        debugPrintln(DEBUGMSG_MOVESELECTION, "  so my move reevaluates to " + reevaluatedPEvMove + ".");
                    addEvaluatedMoveToSortedListOfCol(reevaluatedPEvMove, bestMoves, col, maxBestMoves, restMoves);
                }
            }
        }
        // after the best moves run again with the rest of the moves - just to be sure to not overlook something
        for (ChessPiece p : piecesOnBoard) {
            if (p != null && p.color() == col) {
                for (EvaluatedMove pEvMove : p.getEvaluatedRestMoves()) {
                    EvaluatedMove reevaluatedPEvMove = reevaluateMove(col, bestOpponentMoves, p, pEvMove);
                    if (reevaluatedPEvMove == null)
                        continue;
                    if (DEBUGMSG_MOVESELECTION)
                        debugPrintln(DEBUGMSG_MOVESELECTION, "  so my (rest)move reevaluates to " + reevaluatedPEvMove + ".");
                    addEvaluatedMoveToSortedListOfCol(reevaluatedPEvMove, bestMoves, col, maxBestMoves, restMoves);
                }
            }
        }
        return bestMoves;
    }

    class BestOppMoveResult {
        protected EvaluatedMove evMove = null;
        protected Evaluation evalAfterPrevMoves = null;
    }
    
    @Nullable
    private EvaluatedMove reevaluateMove(boolean col, List<EvaluatedMove> bestOpponentMoves, ChessPiece p, EvaluatedMove pEvMove) {
        if (pEvMove == null)
            return null;
        if (DEBUGMSG_MOVESELECTION)
            debugPrintln(DEBUGMSG_MOVESELECTION, "---- checking " + p + " with stayEval=" + p.staysEval() + " with move " + pEvMove + ": ");
        nrOfLegalMoves[colorIndex(col)]++;  // well it's not really counting the truth, but more for more :-)
        ChessPiece beatenPiece = board.getPieceAt(pEvMove.to());
        Square toSq = board.getBoardSquare(pEvMove.to());
        int opponentMoveCorrection = 0;
        if ( !toSq.isEmpty()
                // old, simple detection if this is a real clash: && evalIsOkForColByMin( (-toSq.getvPiece(beatenPiece.getPieceID()).myPiece().getValue()) - (toSq.getvPiece(p.getPieceID()).getRelEvalOrZero()), p.color(), -EVAL_HALFAPAWN )
                && toSq.takingByPieceWinsTempo(p)
                // Todo?: or is Check-giving?
        ) {
            // opponent needs to take back first - so diminish all other opponent moves by the value of my piece that
            // needs to be taken back
            opponentMoveCorrection =  toSq.getvPiece(p.getPieceID()).getValue();
            if (DEBUGMSG_MOVESELECTION2 && col == getTurnCol())
                debugPrintln(DEBUGMSG_MOVESELECTION2, " ###: "+board.getBoardFEN()+" move " + pEvMove
                        + " starts clash with no tempo loss, so oppMoveCorrection="+opponentMoveCorrection+".");
        }
        else if (col == getTurnCol() && !toSq.isEmpty() && evalIsOkForColByMin(
                (-toSq.getvPiece(beatenPiece.getPieceID()).myPiece().getValue())
                          - (toSq.getvPiece(p.getPieceID()).getRelEvalOrZero()),
                      p.color(), -EVAL_HALFAPAWN ) )
            debugPrintln(DEBUGMSG_MOVESELECTION2, " ###-: "+board.getBoardFEN()+" move " + pEvMove
                    + " would formerly have had correction of " + toSq.getvPiece(p.getPieceID()).getValue() + ".");

        BestOppMoveResult bestOppMove
             = getBestOppMoveResult(col, bestOpponentMoves, pEvMove, opponentMoveCorrection);
        EvaluatedMove reevaluatedPEvMove = new EvaluatedMove(pEvMove);

        if (bestOppMove.evalAfterPrevMoves != null)
            reevaluatedPEvMove.addEval(bestOppMove.evalAfterPrevMoves);

        if (bestOppMove.evMove != null) {
            // take opponents remaining best move into account
            // if even after opponentMoveCorrection it is positive for opponent (otherwise it is counted as 0,
            // because the counter-move of a clash is anyway already calculated into my move

            // check effekt of my move on target square of best opponents move
            VirtualPieceOnSquare pVPceAtOppTarget = getBoardSquare(bestOppMove.evMove.to()).getvPiece(p.getPieceID());
            ConditionalDistance pRmdAtOppTarget = pVPceAtOppTarget.getRawMinDistanceFromPiece();
            if (DEBUGMSG_MOVESELECTION)
                debugPrintln(DEBUGMSG_MOVESELECTION, "  my situation at opponents target: " + pRmdAtOppTarget
                        + ", check axis " + squareName(pEvMove.from()) + squareName(pEvMove.to())
                        + squareName(bestOppMove.evMove.to()) + ".");
            if (pRmdAtOppTarget.dist() == 1 && pRmdAtOppTarget.isUnconditional()
                    && !(isSlidingPieceType(p.getPieceType())
                         && dirsAreOnSameAxis(calcDirFromTo(pEvMove.from(), pEvMove.to()),
                               calcDirFromTo(pEvMove.from(), bestOppMove.evMove.to()))
                        )
            ) {
                // I used to cover the target square, but not any longer
                int contrib = pVPceAtOppTarget.getClashContribOrZero();
                if (DEBUGMSG_MOVESELECTION)
                    debugPrintln(DEBUGMSG_MOVESELECTION, "  leaving behind contribution of: " + contrib + ".");
                reevaluatedPEvMove.subtractEvalAt(contrib, 0);
            }
        }
        if (bestOpponentMoves != null // ==null means we are calculating the opponents best move, mine are not included in the coll to this method then
            && (nrOfLegalMoves(opponentColor(col))<= bestOpponentMoves.toArray().length
                       && bestOppMove.evMove == null)
        ) {
            //it seems, opponent has no more moves (unless our move enables one that was not possible today
            if (pEvMove.isCheckGiving()) { // it could be mate? (unless ... see above)
                //if (!evalIsOkForColByMin(pEvMove.getEval()[0], col, -checkmateEval(opponentColor(col))>>1 ))
                // No, this actually often ruins the anti-3fold-repetition-evaluation and
                // makes the draw-move look positive...: reevaluatedPEvMove.getEval()[0] += checkmateEval(opponentColor(col)) >> 2;
                // So: could still do it unless this move is somehow flagged as 3fold-repetition.
                if (board.moveLeadsToRepetitionNr(reevaluatedPEvMove.from(), reevaluatedPEvMove.to())<2)  // sorry, no flag remembered, we calc hashes here again...
                    reevaluatedPEvMove.addEval(isWhite(col) ? EVAL_HALFAPAWN : -EVAL_HALFAPAWN, 0);  // 0.48h44l
                    // was: reevaluatedPEvMove.addEval(checkmateEval(opponentColor(col)) >> 2, 0);
                    // but this could lead to totally overestimated moves, e.g. moving unprotected Q next to k, just because all bestOppMoves are hindered (but there would be other moves, that are just do not part of the sub set of considered best moves)
            }
            else {  // it could be stalemate!
                int deltaToDraw = -board.boardEvaluation(1);
                if (DEBUGMSG_MOVESELECTION)
                    debugPrintln(DEBUGMSG_MOVESELECTION, "  stalemateish move? " + reevaluatedPEvMove
                        + " changing eval half way towards " + deltaToDraw + ".");
                reevaluatedPEvMove.changeEvalHalfWayTowards(deltaToDraw);
            }
        }
        return reevaluatedPEvMove;
    }

    @NotNull
    private BestOppMoveResult getBestOppMoveResult(boolean col,
                                                   List<EvaluatedMove> bestOpponentMoves,
                                                   EvaluatedMove pEvMove,
                                                   int opponentMoveCorrection) {
        int nrOfBestOpponentMoves = 0;
        BestOppMoveResult bestOppMove;
        Evaluation bestOppMoveEvalAfterPrevMoves;
        bestOppMove = new BestOppMoveResult();
        int bestNextOppMoveEval0 = 0;
        int oppMoveIndex = 0;
        if ( bestOpponentMoves != null ) {
            nrOfBestOpponentMoves = bestOpponentMoves.size();
            if (DEBUGMSG_MOVESELECTION2)
                debugPrintln(DEBUGMSG_MOVESELECTION2, " ##### looking for oppBestMove on: "+board.getBoardFEN()+" after "+pEvMove+":");

            while (oppMoveIndex < nrOfBestOpponentMoves) {
                EvaluatedMove oppMove = bestOpponentMoves.get(oppMoveIndex);
                if (DEBUGMSG_MOVESELECTION2)
                    debugPrint(DEBUGMSG_MOVESELECTION2, " ##### analyzing: "+oppMove+":");
                if (oppMove != null) {
                    ChessPiece piecebeatenByOpponent = board.getPieceAt(oppMove.to());
                    ChessPiece oppPiece = board.getPieceAt(oppMove.from());
                    boolean omIsOk = evalIsOkForColByMin(oppMove.getEvalAt(0), opponentColor(col));
                    // Todo: store mover in move, because this "id-research" will not work for multiple sequent moves of the same piece in later recursions (not yet implemented anyway)
                    VirtualPieceOnSquare oppMoveTargetVPce = (oppPiece.getPieceID() == NO_PIECE_ID) ? null
                                                                : getBoardSquare(oppMove.to()).getvPiece(oppPiece.getPieceID());
                    boolean pEvMoveHindersOrNoAbzugschach = oppMoveTargetVPce == null
                                                        || !oppMoveTargetVPce.hasAbzugChecker()
                                                        || ( isPawn(oppPiece.getPieceType())     // opp is a pawn that is too late to take the pEvMover, but as it movse away,
                                                             && pEvMove.from() == oppMove.to() ) // it cannot take and thus also not trigger Abzugschach
                                                        || //48h75  moveIsReallyHinderingMove(pEvMove,
                                                           moveIsMoreOrLessHinderingMove(pEvMove,  //48h75c+<75
                                                                new EvaluatedMove(oppMoveTargetVPce.getAbzugChecker().getMyPiecePos(),
                                                                getKingPos(col)));
                    if ( (  moveIsReallyHinderingMove(pEvMove, oppMove) //48h75
                            //48h75c+<75 moveIsMoreOrLessHinderingMove(pEvMove, oppMove)
                            || ( moveIsCoveringMoveTarget(pEvMove, oppMove)
                                && ( piecebeatenByOpponent == null   // Todo: be more precise with simulation of clash at oppMove.to with added defender
                                    || abs(piecebeatenByOpponent.getValue()) <= abs(oppPiece.getValue())
                                    || (isPawn(oppPiece.getPieceType()) && isPromotionRankForColor(oppMove.to(), oppPiece.color()))
                                    || isCheckmateEvalFor(oppMove.getEvalAt(0), oppPiece.color()) ) ) )
                         && pEvMoveHindersOrNoAbzugschach ) {
                        if (DEBUGMSG_MOVESELECTION)
                            debugPrintln(DEBUGMSG_MOVESELECTION, "  hindering opponents move "
                                    + oppMove
                                    + ": hindering=" + moveIsMoreOrLessHinderingMove(pEvMove, oppMove)
                                    + "(" + evalEffectOfMovingAwayAgainst(pEvMove, oppMove) + ")"
                                    + ", hinders or no Abzugschach=" + pEvMoveHindersOrNoAbzugschach
                                    + " with AbzugChecker=" + (oppMoveTargetVPce.hasAbzugChecker() ? oppMoveTargetVPce.getAbzugChecker() : "null")
                                    + ", covering target=" + moveIsCoveringMoveTarget(pEvMove, oppMove)
                                    + " && beaten piece at target=" + (oppMoveTargetVPce.hasAbzugChecker() ? oppMoveTargetVPce.getAbzugChecker() : "null" )
                                    + (piecebeatenByOpponent==null ? "nothing taken " : (" (term=" + (abs(piecebeatenByOpponent.getValue()) <= abs(oppPiece.getValue())
                                            || (isPawn(oppPiece.getPieceType()) && isPromotionRankForColor(oppMove.to(), oppPiece.color()))
                                            || isCheckmateEvalFor(oppMove.getRawEval()[0], oppPiece.color())) + ") ") )
                                    + ".");
                    }
                    else if ( ( pEvMove.isCheckGiving()
                                && !moveIsMoreOrLessHinderingMove(oppMove,
                            new EvaluatedMove(pEvMove.to(), getKingPos(oppPiece.color()))) )
                              && pEvMoveHindersOrNoAbzugschach ) {
                        // I check, but oppMove does not block the check, but he has to deal with the check first.
                        // still we grant some bonus, because this oppMove could still be unavoidable after the check
                        // unless I give checkmate - this is most probably unavoidable :-)
                        if ( !pEvMove.isMatingFor(opponentColor(col)) ) {
                            bestNextOppMoveEval0 = bestNextOppMoveEval0IfItSeemsUnavoidable(col, pEvMove, oppMove, omIsOk, bestNextOppMoveEval0);
                            if (DEBUGMSG_MOVESELECTION)
                                debugPrintln(DEBUGMSG_MOVESELECTION, " maybe indirectly hindering opponents move "
                                        + oppMove
                                        + "as I am check giving=" + pEvMove.isCheckGiving()  // I check, but opponent can block the check, so his move is taken into account
                                        + "&& !opp hindering check=" + (!moveIsMoreOrLessHinderingMove(oppMove,
                                                                                   new EvaluatedMove(pEvMove.to(),
                                                                                    getKingPos(oppPiece.color()))))
                                        + ".");
                        }
                    }
                    else {
                        int thisOppMovesCorrection;
                        Square oppToSq = board.getBoardSquare(oppMove.to());
                        final boolean oppMoveIsRealChecker = oppMoveTargetVPce.isRealChecker();
                        final boolean oppMoveIsStillCheckGiving = ( oppMoveIsRealChecker
                                                                    && !moveIsReallyHinderingMove(pEvMove, oppMove)  //48h75
                                                                    //48h75c+<75  && !moveIsMoreOrLessHinderingMove(pEvMove, oppMove)
                                                                    //TODO: check/correct: wrong if king runs into another square covered by the same oppMove (or the same Abzugschach)
                                                                    )
                                                                  || ( oppMoveTargetVPce.hasAbzugChecker()
                                                                        && !pEvMoveHindersOrNoAbzugschach );
                        int secondMoveEval = oppMoveTargetVPce.getChance().getEvalAt(1);
                        secondMoveEval -= secondMoveEval>>2;
                        if ( oppMoveIsStillCheckGiving && !pEvMove.isCheckGiving() ) {
                            thisOppMovesCorrection = 0;  /* maxFor(opponentMoveCorrection, -oppToSq.getvPiece(oppPiece.getPieceID()).getValue(), oppPiece.color()); */
                            debugPrint(DEBUGMSG_MOVESELECTION2, " (opponent move " + oppMove + " wins tempo by checking, changing corr. to "+thisOppMovesCorrection+") ");
                        } else if ( !oppToSq.isEmpty() && oppToSq.takingByPieceWinsTempo(oppPiece) ) {
                            thisOppMovesCorrection = maxFor(opponentMoveCorrection,   // only the least bad option needs to be compensated
                                    -oppToSq.getvPiece(oppPiece.getPieceID()).getValue(),
                                    oppPiece.color());
                            debugPrint(DEBUGMSG_MOVESELECTION2, " (opponent move " + oppMove + " is taking without loosing tempo, changing corr. to "+thisOppMovesCorrection+") ");
                        } else if ( evalIsOkForColByMin(secondMoveEval, opponentColor(col), -(EVAL_HALFAPAWN<<2)) ) {  // presence of a significant next move is like winning a tempo, too
                            thisOppMovesCorrection = maxFor(0,   // with max(0,,col) the effect of 2ndMoveEval cannot superseed the oppMoveCorr
                                    opponentMoveCorrection + secondMoveEval,
                                    col);
                            debugPrint(DEBUGMSG_MOVESELECTION2, " (opponent move " + oppMove
                                    + " also wins tempo with great follow up -> changing corr. to "+thisOppMovesCorrection+") ");
                        } else {
                            // but opponent's move is also a clash that needs a taking back, so he might then still be able to take back and miss nothing
                            if (isOppMoveUnavoidable(pEvMove, oppMove, omIsOk)) {
                                thisOppMovesCorrection = maxFor(0,
                                        opponentMoveCorrection + (oppMove.getEvalAt(0)>>1 ),
                                        col);  // do not correct to below 0
                            }
                            else
                                thisOppMovesCorrection = opponentMoveCorrection;
                        }

                        if (board.hasPieceOfColorAt(opponentColor(col), pEvMove.to())) {
                            // check if my moves eliminates target that best move of opponent has a now invalid contribution to
                            // (i.e. he moves there to cover his piece on that square, but it is too late, it is already taken)
                            int oppMoveToPos = oppMove.to();
                            Square pEvToSq = getBoardSquare(pEvMove.to());
                            VirtualPieceOnSquare oppVPceAtMyTarget = pEvToSq.getvPiece(
                                    getBoardSquare(oppMove.from()).getPieceID() );
                            ConditionalDistance oppRmdAtMyTarget = oppVPceAtMyTarget.getRawMinDistanceFromPiece();
                            if ( oppVPceAtMyTarget.coverOrAttackDistance() == 2
                                    && !oppRmdAtMyTarget.hasNoGo()
                                    && oppRmdAtMyTarget.isUnconditional()
                                    && oppRmdAtMyTarget.getLastMoveOrigins().stream()   // lastMoveOrigins contain pEvMove's target pos
                                        .anyMatch(lmo -> lmo.getMyPos() == oppMoveToPos)
                                    && pEvToSq.countDirectAttacksWithColor(opponentColor(col)) < pEvToSq.countDirectAttacksWithColor(col) // just an indication that oppMoes help was needed - should be solved with a recalc of clash with opponent or better the below mentioned distant contribution calculation
                            ) {
                                // TODO!: getClashContrib does not work here, because it is always 0 - it is never calculated for extra-covering of own pieces, esp not in 2 moves... --> needed
                                //contrib[0] = oppVPceAtMyTarget.getClashContrib();
                                // so let's just assume it was a good move and covers the target square beneficially ... might not be so true ...
                                int oppContribCorrectionAtTarget = minFor(pEvToSq.clashEval(),
                                                                 -oppMove.getEvalAt(0) >> 1, col);
                                if (DEBUGMSG_MOVESELECTION)
                                    debugPrintln(DEBUGMSG_MOVESELECTION, "OppMove's distance to my move's target piece "
                                            + oppRmdAtMyTarget + " via " + squareName(oppMoveToPos)
                                            + " has estimated contrib : " + (-oppContribCorrectionAtTarget)
                                            + "@0 at " + squareName(pEvMove.to()) + ".");
                                thisOppMovesCorrection += oppContribCorrectionAtTarget;
                            }
                        }

                        // sum up final evaluation of oppMove
                        Evaluation corrOppMoveEval = new Evaluation(oppMove.eval());
                        // first consider assumed effect on eval0 of moving out of the way
                        boolean changedDueToMovingAway = false;
                        // 48h75
                        if ( moveIsMoreOrLessHinderingMove(pEvMove, oppMove)
                                && !moveIsReallyHinderingMove(pEvMove, oppMove) ) {
                            debugPrint(DEBUGMSG_MOVESELECTION2, " (adding effect of "
                                    +evalEffectOfMovingAwayAgainst(pEvMove, oppMove)+" of moving out of the way) ");
                            corrOppMoveEval.addEval(evalEffectOfMovingAwayAgainst(pEvMove, oppMove), 0);
                            changedDueToMovingAway = true;
                        }
                        if (oppMove.isCheckGiving() && !oppMoveIsStillCheckGiving) {
                            // if checkgiving changed=lost, we have to reduce the evaluation
                            corrOppMoveEval.addEval(thisOppMovesCorrection, 0);
                            int oppEval0Reduction = corrOppMoveEval.getEvalAt(0) - (corrOppMoveEval.getEvalAt(0) >> 3);
                            if (evalIsOkForColByMin(oppEval0Reduction, opponentColor(col))) {
                                // lost check giving advantage, e.g. if oppMove was a fork, then this is not forcing any more... (because king walked away e.g.)
                                // as it is unclear, deminish success of opponent by biggest part 87%, but not fully
                                corrOppMoveEval.addEval(-oppEval0Reduction, 0);
                                debugPrint(DEBUGMSG_MOVESELECTION2, " Reducing benefit of " + oppMove
                                        + " to " + corrOppMoveEval + " because it lost check-ability. ");
                            }
                        }
                        else if (oppMove.isCheckGiving() && oppMoveIsStillCheckGiving && !pEvMove.isCheckGiving()) {
                            // Check as Zwischenzug will afterwards still allow taking back in the clash the pEvMove started, so no correction
                            if (DEBUGMSG_MOVESELECTION)
                                debugPrintln(DEBUGMSG_MOVESELECTION, " opponent checks as Zwischenzug with " + oppMove + ", so we do not apply move correction.");
                        }
                        else {
                            corrOppMoveEval.addEval(thisOppMovesCorrection, 0);
                        }

                        // remember bestOppMove
                        if ( bestOppMove.evMove == null
                              ||  corrOppMoveEval.isBetterForColorThan(opponentColor(col), bestOppMove.evalAfterPrevMoves)
                        ) {
                            if (oppMove.isCheckGiving() && !oppMoveIsStillCheckGiving) {  // if checkgiving changed, we have to instantiate a new changed move
                                bestOppMove.evMove = new EvaluatedMove(oppMove);
                                bestOppMove.evMove.setIsCheckGiving(false);
                            }
                            else {
                                bestOppMove.evMove = oppMove;
                            }
                            bestOppMove.evalAfterPrevMoves = corrOppMoveEval;
                            if (DEBUGMSG_MOVESELECTION2 && col == getTurnCol())
                                debugPrintln(DEBUGMSG_MOVESELECTION2, " #####: "
                                        +"Best oppMove so far:" + bestOppMove.evMove
                                        +" corrected to " + bestOppMove.evalAfterPrevMoves + ".");
                        }
                        else {
                            if (DEBUGMSG_MOVESELECTION2)
                                debugPrintln(DEBUGMSG_MOVESELECTION2, (corrOppMoveEval.equals(oppMove.eval()) ? ""
                                        : " (corrected to " + corrOppMoveEval + ")")
                                        +" --> skipping.");
                        }

                        /*if ( (opponentMoveCorrection == 0
                                && thisOppMovesCorrection == 0
                                && !changedDueToMovingAway )
                            || (bestOppMove.evMove != null
                                && bestOppMove.evMove.isCheckGiving() && !pEvMove.isCheckGiving()) )
                            break;  // we can stop searching (oppMoves are sorted. The sort order can here only be "disturbed" by a checking move, which is not catching the oppMoveCorrection
                        */
                        //todo: could be speeded up by quicker loop to look for opp's checking moves
                    }
                }
                oppMoveIndex++;
            }
        }

        if (DEBUGMSG_MOVESELECTION)
            debugPrintln(DEBUGMSG_MOVESELECTION, "  best opponents move then is "
                + (bestOppMove.evMove==null ? "none" : bestOppMove.evMove )
                + "+" + ( (bestOppMove.evMove!=null && (bestOppMove.evMove.isCheckGiving() && !pEvMove.isCheckGiving())) ? " no" : "")
                    + "corrected to " + bestOppMove.evalAfterPrevMoves
                    + ( bestNextOppMoveEval0 != 0 ? " (and next best oppMove "+bestNextOppMoveEval0 : ")" )
                    + ".");

        if (bestOppMove.evMove != null) {
            if ( !evalIsOkForColByMin(bestOppMove.evalAfterPrevMoves.getEvalAt(0), opponentColor(col) ) ) {
                // TODO: try if this is still needed or even bad -> tried in v0.48h43b - was much worse, but why?
                bestOppMove.evalAfterPrevMoves = new Evaluation(ANYWHERE);  // set eval to 0 if opponent has only bad moves for himself.
                if (DEBUGMSG_MOVESELECTION)
                    debugPrintln(DEBUGMSG_MOVESELECTION, " only bad moves for opponent -> " + bestOppMove.evalAfterPrevMoves);
            }
            // extra danger -> opponent move gives check!  // Todo: check/test this
            if (bestOppMove.evMove.isCheckGiving()) {
                if ( oppMoveIndex < nrOfBestOpponentMoves - 1) {
                    // todo: check if next best move is actually also blocked by my move
                    EvaluatedMove nextBestOppMove = bestOpponentMoves.get(oppMoveIndex+1);
                    // if this move is checking, add the half of the next best move to it
                    if (evalIsOkForColByMin(nextBestOppMove.getEvalAt(0), col, -EVAL_TENTH)) {
                        Evaluation nbOppMoveEvalHalf = new Evaluation(nextBestOppMove.eval())
                                .devideBy(2);
                        if (DEBUGMSG_MOVESELECTION)
                            debugPrintln(DEBUGMSG_MOVESELECTION, "  opponent's check giving move is awarded half of : " + nextBestOppMove + ".");
                        for (int i = 0; i < pEvMove.getRawEval().length; i++)
                            bestOppMove.evalAfterPrevMoves.addEval((nextBestOppMove.getEvalAt(i)) >> 1, i);
                        debugPrintln(DEBUGMSG_MOVESELECTION, "  opponent's check giving move is awarded its half eval : " + nbOppMoveEvalHalf + ".");
                        bestOppMove.evalAfterPrevMoves.addEval(nbOppMoveEvalHalf);
                        if (DEBUGMSG_MOVESELECTION)
                            debugPrintln(DEBUGMSG_MOVESELECTION, " oC->adding 1/2 2nd-best -> " + bestOppMove.evalAfterPrevMoves);
                    }
                }
            }
        }

        if ( bestNextOppMoveEval0 != 0) {
            if ( bestOppMove.evalAfterPrevMoves == null )  // there was no evaluation, mostly/surely because bestOppMove is also null, but still we signal the secondOppMoves result (as oppMoveList might have been incomplete at this point)
                bestOppMove.evalAfterPrevMoves = new Evaluation(ANYWHERE);
            if (DEBUGMSG_MOVESELECTION)
                debugPrintln(DEBUGMSG_MOVESELECTION, " Adding 1/2 of propable next best move " + bestNextOppMoveEval0);
//                debugPrintln(DEBUGMSG_MOVESELECTION, " Adding 3/4 of propable next best move " + bestNextOppMoveEval0);
            bestOppMove.evalAfterPrevMoves.addEval(bestNextOppMoveEval0>>1 /*- (bestNextOppMoveEval0>>2)*/, 0 );
        }

        return bestOppMove;
    }

    private int bestNextOppMoveEval0IfItSeemsUnavoidable(final boolean col,
                                                         final EvaluatedMove pEvMove,
                                                         final EvaluatedMove oppMove,
                                                         final boolean omIsOk,
                                                         int oldBestNextOppMoveEval0
    ) {
        if (isOppMoveUnavoidable(pEvMove, oppMove, omIsOk)
        ) {
            int bestNextOppMoveEval0 = oppMove.getEvalAt(0);
            ChessPiece myEndangeredPce = board.getPieceAt(oppMove.to());
            if (myEndangeredPce != null)
                bestNextOppMoveEval0 = minFor( bestNextOppMoveEval0, myEndangeredPce.getBestMoveRelEval() , opponentColor(col) );
            debugPrint(DEBUGMSG_MOVESELECTION2, " (opponent move " + oppMove
                    + " is propably still possible after my checking move "
                    + "-> will change bestOppMove by "+ bestNextOppMoveEval0 +":) ");
            return maxFor(oldBestNextOppMoveEval0, bestNextOppMoveEval0, opponentColor(col));
        }
        return oldBestNextOppMoveEval0;
    }

    private boolean isOppMoveUnavoidable(EvaluatedMove pEvMove, EvaluatedMove oppMove, boolean omIsOk) {
        ChessPiece myEndangeredPce = board.getPieceAt(oppMove.to());
        ConditionalDistance endPce2pEvMover = myEndangeredPce == null ? null
                : board.getBoardSquare(pEvMove.from())
                    .getvPiece(myEndangeredPce.getPieceID()).getRawMinDistanceFromPiece();
        return omIsOk
                && (myEndangeredPce == null
                    || !(myEndangeredPce.canMoveAwayReasonably()   // piece threatened by opponent can move away (after check) anyway
                         || (endPce2pEvMover.dist() == 1 && endPce2pEvMover.isUnconditional() ) ) );    //  or mover frees the way
    }

    boolean doMove (@NotNull Move m) {
        return doMove(m.from(), m.to(), m.promotesTo());
    }

    boolean doMove ( int frompos, int topos, int promoteToPceType){
        debugPrintln(DEBUGMSG_BOARD_MOVES, "DOING MOVE " + squareName(frompos) + squareName(topos) + ". ");
        // sanity/range checks for move
        if (frompos < 0 || topos < 0
                || frompos >= NR_SQUARES || topos >= NR_SQUARES) { // || figuresOnBoard[frompos].getColor()!=turn  ) {
            internalErrorPrintln(String.format("Fehlerhafter Zug: %s%s ist außerhalb des Boards %s.\n", squareName(frompos), squareName(topos), getBoardFEN()));
            return false;
        }
        final int pceID = getPieceIdAt(frompos);
        final int pceType = getPieceTypeAt(frompos);
        if (pceID == NO_PIECE_ID) { // || figuresOnBoard[frompos].getColor()!=turn  ) {
            internalErrorPrintln(String.format("Fehlerhafter Zug: auf %s steht keine Figur auf Board %s.\n", squareName(frompos), getBoardFEN()));
            return false;
        }
        if (boardSquares[topos].getUnconditionalDistanceToPieceIdIfShortest(pceID) != 1
                && colorlessPieceType(pceType) != KING) { // || figuresOnBoard[frompos].getColor()!=turn  ) {
            // TODO: check king for allowed moves... excluded here, because castling is not obeyed in distance calculation, yet.
            internalErrorPrintln(String.format("Fehlerhafter Zug: %s -> %s nicht möglich auf Board %s (%s).\n", squareName(frompos), squareName(topos), getBoardFEN(),
                    boardSquares[topos].getvPiece(pceID).getRawMinDistanceFromPiece()));
            //TODO!!: this allow illegal moves, but for now overcomes the bug to not allow enpassant beating by the opponent...
            if (!(colorlessPieceType(pceType) == PAWN && fileOf(topos) == enPassantFile))
                return false;
        }
        final int toposPceID = getPieceIdAt(topos);
        final int toposType = getPieceTypeAt(topos);

        // take piece, but be careful, if the target is my own rook, it could be castling!
        boolean isBeatingSameColor = toposPceID != NO_PIECE_ID
                                     && colorOfPieceType(pceType) == colorOfPieceType(toposType);
        if (toposPceID != NO_PIECE_ID && !isBeatingSameColor ) {
            // if it is a rook, remove castling rights
            if ( toposType == ROOK ) {
                if ( fileOf(topos) > fileOf(getKingPos(WHITE)))
                    kingsideCastlingAllowed[CIWHITE] = false;
                else if ( fileOf(topos) < fileOf(getKingPos(WHITE)))
                    queensideCastlingAllowed[CIWHITE] = false;
            }
            else if ( toposType == ROOK_BLACK ) {
                if ( fileOf(topos) > fileOf(getKingPos(BLACK)))
                    kingsideCastlingAllowed[CIBLACK] = false;
                else if ( fileOf(topos) < fileOf(getKingPos(BLACK)))
                    queensideCastlingAllowed[CIBLACK] = false;
            }
            takePieceAway(topos);

        /*old code to update pawn-eval-parameters
        if (takenFigNr==NR_PAWN && toRow==getWhitePawnRowAtCol(toCol))
            refindWhitePawnRowAtColBelow(toCol,toRow+1);  // try to find other pawn in column where the pawn was beaten
        else if (takenFigNr==NR_PAWN_BLACK && toRow==getBlackPawnRowAtCol(toCol))
            refindBlackPawnRowAtColBelow(toCol,toRow-1);*/
        }

        // en-passant
        // is possible to occur in two notations to left/right (then taken pawn has already been treated above) ...
        if (pceType == PAWN
                && rankOf(frompos) == 4 && rankOf(topos) == 4
                && fileOf(topos) == enPassantFile) {
            topos += UP;
            //setFurtherWhitePawnRowAtCol(toCol, toRow);     // if the moved pawn was the furthest, remember new position, otherwise the function will leave it
        } else if (pceType == PAWN_BLACK
                && rankOf(frompos) == 3 && rankOf(topos) == 3
                && fileOf(topos) == enPassantFile) {
            topos += DOWN;
            //setFurtherBlackPawnRowAtCol(toCol, toRow);     // if the moved pawn was the furthest, remember new position, otherwise the function will leave it
        }
        // ... or diagonally als normal
        else if (pceType == PAWN
                && rankOf(frompos) == 4 && rankOf(topos) == 5
                && fileOf(topos) == enPassantFile) {
            takePieceAway(topos + DOWN);
        /*setFurtherWhitePawnRowAtCol(toCol, toRow);     // if the moved pawn was the furthest, remember new position, otherwise the function will leave it
        if (toRow+1==getBlackPawnRowAtCol(toCol))
            setFurtherBlackPawnRowAtCol(toCol, NO_BLACK_PAWN_IN_COL);*/
        } else if (pceType == PAWN_BLACK
                && rankOf(frompos) == 3 && rankOf(topos) == 2
                && fileOf(topos) == enPassantFile) {
            takePieceAway(topos + UP);
        /*setFurtherBlackPawnRowAtCol(toCol, toRow);     // if the moved pawn was the furthest, remember new position, otherwise the function will leave it
        if (toRow-1==getWhitePawnRowAtCol(toCol))
            setFurtherWhitePawnRowAtCol(toCol, NO_WHITE_PAWN_IN_COL);*/
        }

        //boardMoves.append(" ").append(squareName(frompos)).append(squareName(topos));

        // check if this is a 2-square pawn move ->  then enPassent is possible for opponent at next move
        if (pceType == PAWN && rankOf(frompos) == 1 && rankOf(topos) == 3
                || pceType == PAWN_BLACK && rankOf(frompos) == 6 && rankOf(topos) == 4)
            enPassantFile = fileOf(topos);
        else
            enPassantFile = -1;

        // castling:
        // i) also move rook  ii) update castling rights
        // test for Chess960 manually e.g. after: nbrkbrqn/pppppppp/8/8/8/8/PPPPPPPP/NBRKBRQN w CFcf - 0 1
        boolean didCastle = false;
        //TODO: check if squares where king moves along are free from check
        if (pceType == KING_BLACK) {
            if ( frompos<topos && isLastRank(frompos) && isLastRank(topos)
                    && ( toposType == ROOK_BLACK || topos == frompos+2 )
                    && allSquaresEmptyFromTo(frompos,topos)
                    && isKingsideCastlingPossible(BLACK)
            ) {
                if ( toposPceID == NO_PIECE_ID && topos == frompos+2 )  // seems to be std-chess notation (king moves 2 aside)
                    topos = findRook(frompos+1, coordinateString2Pos("h8"));
                if ( CASTLING_KINGSIDE_ROOKTARGET[CIBLACK] ==blackKingPos ) { // we have problem here, as this can happen in Chess960, but would kick our own king piece of the board
                    takePieceAway(topos); // eliminate rook :*\
                    basicMoveFromTo(frompos, CASTLING_KINGSIDE_KINGTARGET[CIBLACK]);  // move king instead
                    frompos = NOWHERE;
                    topos = CASTLING_KINGSIDE_ROOKTARGET[CIBLACK];
                }
                else {
                    basicMoveFromTo(ROOK_BLACK, getBoardSquare(topos).getPieceID(), topos, CASTLING_KINGSIDE_ROOKTARGET[CIBLACK]);
                    //target position is always the same, even for chess960 - touches rook first, but nobody knows ;-)
                    topos = CASTLING_KINGSIDE_KINGTARGET[CIBLACK];
                }
                didCastle = true;
            }
            else if ( queensideCastlingAllowed[CIBLACK] && frompos>topos && isLastRank(frompos) && isLastRank(topos)
                    && ( toposType == ROOK_BLACK || topos == frompos-2 )
                    && allSquaresEmptyFromTo(frompos,topos)
                    && ( isSquareEmpty(CASTLING_QUEENSIDE_KINGTARGET[CIBLACK])
                    || getBoardSquare(CASTLING_QUEENSIDE_KINGTARGET[CIBLACK]).myPieceType()==KING_BLACK
                    || getBoardSquare(CASTLING_QUEENSIDE_KINGTARGET[CIBLACK]).myPieceType()==ROOK_BLACK )
                    && ( isSquareEmpty(CASTLING_QUEENSIDE_ROOKTARGET[CIBLACK])
                    || getBoardSquare(CASTLING_QUEENSIDE_ROOKTARGET[CIBLACK]).myPieceType()==KING_BLACK
                    || getBoardSquare(CASTLING_QUEENSIDE_ROOKTARGET[CIBLACK]).myPieceType()==ROOK_BLACK )
            ) {
                if (toposPceID == NO_PIECE_ID && topos == frompos - 2)  // seems to be std-chess notation (king moves 2 aside)
                    topos = findRook(coordinateString2Pos("a8"), frompos - 1);
                if (CASTLING_QUEENSIDE_ROOKTARGET[CIBLACK] == blackKingPos) { // we have problem here, as this can happen in Chess960, but would kick our own king piece of the board
                    takePieceAway(topos); // eliminate rook :*\
                    basicMoveFromTo(frompos, CASTLING_QUEENSIDE_KINGTARGET[CIBLACK]);  // move king instead
                    frompos = NOWHERE;
                    topos = CASTLING_QUEENSIDE_ROOKTARGET[CIBLACK];
                }
                else {
                    basicMoveFromTo(ROOK_BLACK, getBoardSquare(topos).getPieceID(), topos, CASTLING_QUEENSIDE_ROOKTARGET[CIBLACK]);
                    //target position is always the same, even for chess960 - touches rook first, but nobody knows ;-)
                    topos = CASTLING_QUEENSIDE_KINGTARGET[CIBLACK];
                }
                didCastle = true;
            }
            kingsideCastlingAllowed[CIBLACK] = false;
            queensideCastlingAllowed[CIBLACK] = false;
        } else if (pceType == KING) {
            if ( kingsideCastlingAllowed[CIWHITE] && frompos<topos && isFirstRank(frompos) && isFirstRank(topos)
                    && ( toposType == ROOK || topos == frompos+2 )
                    && allSquaresEmptyFromTo(frompos,topos)
                    && isKingsideCastlingPossible(WHITE)
            ) {
                if ( toposPceID == NO_PIECE_ID && topos == frompos+2 )  // seems to be std-chess notation (king moves 2 aside)
                    topos = findRook(frompos+1, coordinateString2Pos("h1"));
                if ( CASTLING_KINGSIDE_ROOKTARGET[CIWHITE] ==whiteKingPos ) { // we have problem here, as this can happen in Chess960, but would kick our own king piece of the board
                    takePieceAway(topos); // eliminate rook :*\
                    basicMoveFromTo(frompos, CASTLING_KINGSIDE_KINGTARGET[CIWHITE]);  // move king instead
                    frompos = NOWHERE;
                    topos = CASTLING_KINGSIDE_ROOKTARGET[CIWHITE];
                } else {
                    basicMoveFromTo(ROOK, getBoardSquare(topos).getPieceID(), topos, CASTLING_KINGSIDE_ROOKTARGET[CIWHITE]);
                    //target position is always the same, even for chess960 - touches rook first, but nobody knows ;-)
                    topos = CASTLING_KINGSIDE_KINGTARGET[CIWHITE];
                }
                didCastle = true;
            }
            else if ( queensideCastlingAllowed[CIWHITE] && frompos>topos && isFirstRank(frompos) && isFirstRank(topos)
                    && ( toposType == ROOK || topos == frompos-2 )
                    && allSquaresEmptyFromTo(frompos,topos)
                    && ( isSquareEmpty(CASTLING_QUEENSIDE_KINGTARGET[CIWHITE])
                         || getBoardSquare(CASTLING_QUEENSIDE_KINGTARGET[CIWHITE]).myPieceType()==KING
                         || getBoardSquare(CASTLING_QUEENSIDE_KINGTARGET[CIWHITE]).myPieceType()==ROOK )
                    && ( isSquareEmpty(CASTLING_QUEENSIDE_ROOKTARGET[CIWHITE])
                        || getBoardSquare(CASTLING_QUEENSIDE_ROOKTARGET[CIWHITE]).myPieceType()==KING
                        || getBoardSquare(CASTLING_QUEENSIDE_ROOKTARGET[CIWHITE]).myPieceType()==ROOK )
            ) {
                if (toposPceID == NO_PIECE_ID && topos == frompos - 2)  // seems to be std-chess notation (king moves 2 aside)
                    topos = findRook(coordinateString2Pos("a1"), frompos - 1);
                if (CASTLING_QUEENSIDE_ROOKTARGET[CIWHITE] == whiteKingPos) { // we have problem here, as this can happen in Chess960, but would kick our own king piece of the board
                    takePieceAway(topos); // eliminate rook :*\
                    basicMoveFromTo(frompos, CASTLING_QUEENSIDE_ROOKTARGET[CIWHITE]);  // move king instead
                    frompos = NOWHERE;
                    topos = CASTLING_QUEENSIDE_ROOKTARGET[CIWHITE];
                } else {
                    basicMoveFromTo(ROOK, getBoardSquare(topos).getPieceID(), topos, CASTLING_QUEENSIDE_ROOKTARGET[CIWHITE]);
                    //target position is always the same, even for chess960 - touches rook first, but nobody knows ;-)
                    topos = CASTLING_QUEENSIDE_KINGTARGET[CIWHITE];
                }
                didCastle = true;
            }
            kingsideCastlingAllowed[CIWHITE] = false;
            queensideCastlingAllowed[CIWHITE] = false;
        } else if (kingsideCastlingAllowed[CIBLACK] && frompos == 7) {
            kingsideCastlingAllowed[CIBLACK] = false;
        } else if (queensideCastlingAllowed[CIBLACK] && frompos == 0) {
            queensideCastlingAllowed[CIBLACK] = false;
        } else if (kingsideCastlingAllowed[CIWHITE] && frompos == 63) {
            kingsideCastlingAllowed[CIWHITE] = false;
        } else if (queensideCastlingAllowed[CIWHITE] && frompos == 56) {
            queensideCastlingAllowed[CIWHITE] = false;
        }

        if (isBeatingSameColor && !didCastle) {
            internalErrorPrintln(String.format("Fehlerhafter Zug: %s%s schlägt eigene Figur auf %s.\n", squareName(frompos), squareName(topos), getBoardFEN()));
            return false;
        }

        if ( isPawn(pceType) && fileOf(frompos) != fileOf(topos) ) {
            // a beating pawn move
            countPawnsInFile[colorIndexOfPieceType(pceType)][fileOf(frompos)]--;
            countPawnsInFile[colorIndexOfPieceType(pceType)][fileOf(topos)]++;
        }

        if ( isPawn(pceType) || didCastle || toposPceID != NO_PIECE_ID ) {
            resetHashHistory();
            countBoringMoves = 0;
        }
        else {
            countBoringMoves++;
        }

        // move
        if (didCastle && frompos==NOWHERE ) {
            // special Chess960 case where Rook almost moved on kings position during castling and thus was eliminated instead
            spawnPieceAt(isWhite(getTurnCol()) ? ROOK : ROOK_BLACK, topos);
        }
        else
            basicMoveFromTo(pceType, pceID, frompos, topos);

        // promote to
        if ( isPawn(pceType)
                && (isLastRank(topos) || isFirstRank(topos))
        ) {
                if (promoteToPceType <= 0)
                    promoteToPceType = QUEEN;
                takePieceAway(topos);
                if (pceType == PAWN_BLACK)
                    spawnPieceAt(isPieceTypeWhite(promoteToPceType) ? promoteToPceType + BLACK_PIECE : promoteToPceType, topos);
                else
                    spawnPieceAt(isPieceTypeBlack(promoteToPceType) ? promoteToPceType - BLACK_PIECE : promoteToPceType, topos);
                completeCalc();
        } else {
            promoteToPceType = 0;
        }

        turn = !turn;
        if (isWhite(turn))
            fullMoves++;

        //not here: calcBestMove();

        // in debug mode compare with freshly created board from same fenString
        if (DEBUG_BOARD_COMPARE_FRESHBOARD)
            this.equals(new ChessBoard("CmpBoard", this.getBoardFEN()));

        fenPosAndMoves += " " + squareName(frompos) + squareName(topos)
                + (promoteToPceType > 0 ? (fenCharFromPceType(promoteToPceType | BLACK_PIECE)) : "");

        return true;
    }


    public boolean isKingsideCastlingPossible(boolean color) {
        int kingPos = getKingPos(color);
        int ci = colorIndex(color);
        return (kingsideCastlingAllowed[ci]
                && !isCheck(color)
                && (isSquareEmpty(CASTLING_KINGSIDE_KINGTARGET[ci])
                    || ( getBoardSquare(CASTLING_KINGSIDE_KINGTARGET[ci]).myPiece().color() == color
                         &&   (isKing(getBoardSquare(CASTLING_KINGSIDE_KINGTARGET[ci]).myPieceType())
                               || isRook(getBoardSquare(CASTLING_KINGSIDE_KINGTARGET[ci]).myPieceType()))) )
                && (isSquareEmpty(CASTLING_KINGSIDE_ROOKTARGET[ci])
                    || (getBoardSquare(CASTLING_KINGSIDE_ROOKTARGET[ci]).myPiece().color() == color
                        && (isKing(getBoardSquare(CASTLING_KINGSIDE_ROOKTARGET[ci]).myPieceType())
                               || isRook(getBoardSquare(CASTLING_KINGSIDE_ROOKTARGET[ci]).myPieceType()))) )
                && allSquaresEmptyOrRookFromTo(kingPos, CASTLING_KINGSIDE_KINGTARGET[ci])
                && allSquaresFromToWalkable4KingOfColor(kingPos, CASTLING_KINGSIDE_KINGTARGET[ci], color )
        );
    }


    /**
     * searches for a rook and returns position
     * @param fromPosIncl startpos inclusive
     * @param toPosIncl endpos inclusive
     * @return position of the first rook found; NOWHERE if not found
     */
    int findRook(int fromPosIncl, int toPosIncl) {
        int dir = calcDirFromTo(fromPosIncl, toPosIncl);
        if (dir==NONE)
            return NOWHERE;
        int p=fromPosIncl;
        while (p!=toPosIncl){
            if (colorlessPieceType(getBoardSquare(p).myPieceType()) == ROOK)
                return p;
            p+=dir;
        }
        if (colorlessPieceType(getBoardSquare(p).myPieceType()) == ROOK)
            return p;
        return NOWHERE;
    }

    boolean allSquaresEmptyFromTo(final int fromPosExcl, final int toPosExcl) {
        int dir = calcDirFromTo(fromPosExcl, toPosExcl);
        if (dir==NONE)
            return false;
        int p=fromPosExcl+dir;
        while (p!=toPosExcl) {
            if (!isSquareEmpty(p))
                return false;
            p += dir;
        }
        return true;
    }

    /**
     * beware, this is an inprecise hack, there are rare cases, where the wrong rook could already in the way between the castelling moves
     * @param fromPosExcl exclusive
     * @param toPosExcl exclusive
     * @return true if castelling seems possible and there are only empty squares or rooks.
     */
    private boolean allSquaresEmptyOrRookFromTo(final int fromPosExcl, final int toPosExcl) {
        int dir = calcDirFromTo(fromPosExcl, toPosExcl);
        if (dir==NONE)
            return false;
        int p=fromPosExcl+dir;
        while (p!=toPosExcl) {
            if (!isSquareEmpty(p) && !(colorlessPieceType(getPieceAt(p).getPieceType())==ROOK) )
                return false;
            p += dir;
        }
        return true;
    }

    /**
     * no attacks from color here? even not from a king-pinned piece , so designed to check if the king can go here.
     * @param fromPosExcl exclusive
     * @param toPosIncl exclusive
     * @return true if castelling seems possible and there are only empty squares or rooks.
     */
    private boolean allSquaresFromToWalkable4KingOfColor(final int fromPosExcl, final int toPosIncl, boolean color) {
        int dir = calcDirFromTo(fromPosExcl, toPosIncl);
        if (dir==NONE)
            return false;
        int p=fromPosExcl;
        do  {
            p += dir;
            if ( !getBoardSquare(p).walkable4king(color) )
                return false;
        } while (p!=toPosIncl);
        return true;
    }

    public boolean doMove(@NotNull String move){
        int startpos = 0;
        // skip spaces
        while (startpos < move.length() && move.charAt(startpos) == ' ')
            startpos++;
        move = move.substring(startpos);
        if (isWhite(turn)) {
            debugPrint(DEBUGMSG_BOARD_MOVES, " " + getFullMoves() + ".");
        }
        debugPrint(DEBUGMSG_BOARD_MOVES, " " + move);
        // an empty move string is not a legal move
        if (move.isEmpty())
            return false;

        Move m = new Move(move);
        if (m.isMove()) {
            // primitive move string wa successfully interpreted
        } else if (move.charAt(0) >= 'a' && move.charAt(0) < ('a' + NR_RANKS)) {
            int promcharpos;
            if (move.charAt(1) == 'x') {
                // a pawn beats something
                if (move.length() == 3) {
                    // very short form like "cxd" is not supported, yet
                    return false;
                }
                // a pawn beats something, like "hxg4"
                m.setTo(coordinateString2Pos(move, 2));
                m.setFrom(fileRank2Pos(move.charAt(0) - 'a', rankOf(m.to()) + (isWhite(getTurnCol()) ? -1 : +1)));
                promcharpos = 4;
            } else {
                // simple pawn move, like "d4"
                m.setTo(coordinateString2Pos(move, 0));
                m.setFrom(m.to() + (isWhite(getTurnCol()) ? +NR_FILES : -NR_FILES));  // normally it should come from one square below
                if (isWhite(getTurnCol()) && rankOf(m.to()) == 3) {
                    // check if it was a 2-square move...
                    if (getPieceTypeAt(m.from()) == EMPTY)
                        m.setFrom(m.from() + NR_FILES);   // yes, it must be even one further down
                } else if (isBlack(getTurnCol()) && rankOf(m.to()) == NR_RANKS - 4) {
                    // check if it was a 2-square move...
                    if (getPieceTypeAt(m.from()) == EMPTY)
                        m.setFrom(m.from() - NR_FILES);   // yes, it must be even one further down
                }
                promcharpos = 2;
            }
            // promotion character indicates what a pawn should be promoted to
            if ((isBlack(getTurnCol()) && isFirstRank(m.to())
                    || isWhite(getTurnCol()) && isLastRank(m.to()))) {
                char promoteToChar = move.length() > promcharpos ? move.charAt(promcharpos) : 'q';
                if (promoteToChar == '=') // some notations use a1=Q isntead of a1Q
                    promoteToChar = move.length() > promcharpos + 1 ? move.charAt(promcharpos + 1) : 'q';
                m.setPromotesTo(getPceTypeFromPromoteChar(promoteToChar));
            }
        } else if (move.length() >= 3 && move.charAt(1) == '-' &&
                (move.charAt(0) == '0' && move.charAt(2) == '0'
                        || move.charAt(0) == 'O' && move.charAt(2) == 'O'
                        || move.charAt(0) == 'o' && move.charAt(2) == 'o')) {
            // castling - 0-0(-0) notation does not work for chess960 here, but this should be ok
            if (isWhite(getTurnCol()))
                m.setFrom(A1SQUARE + 4);
            else   // black
                m.setFrom(4);
            if (move.length() >= 5 && move.charAt(3) == '-' && move.charAt(4) == move.charAt(0))
                m.setTo(m.from() - 2);  // long castling
            else
                m.setTo(m.from() + 2);  // short castling
        } else {
            // must be a normal, non-pawn move
            int movingPceType = pceTypeFromPieceSymbol(move.charAt(0));
            if (movingPceType == EMPTY)
                internalErrorPrintln(format(chessBasicRes.getString("errorMessage.moveParsingError") + " <{0}>", move.charAt(0)));
            if (isBlack(getTurnCol()))
                movingPceType += BLACK_PIECE;
            int fromFile = -1;
            int fromRank = -1;
            if (move.length()<=2) {
                internalErrorPrintln("Error in move String <" + move + ">. ");
                return false;
            }
            if (isFileChar(move.charAt(2))) {
                // the topos starts only one character later, so there must be an intermediate information
                if (move.charAt(1) == 'x') {   // its beating something - actually we do not care if this is true...
                } else if (isFileChar(move.charAt(1)))  // a starting file
                    fromFile = move.charAt(1) - 'a';
                else if (isRankChar(move.charAt(1)))  // a starting rank
                    fromRank = move.charAt(1) - '1';
                m.setTo(coordinateString2Pos(move, 2));
            } else if (move.charAt(2) == 'x') {
                // a starting file or rank + a beating x..., like "Rfxf2"
                if (isFileChar(move.charAt(1)))      // a starting file
                    fromFile = move.charAt(1) - 'a';
                else if (isRankChar(move.charAt(1))) // a starting rank
                    fromRank = move.charAt(1) - '1';
                m.setTo(coordinateString2Pos(move, 3));
            } else {
                m.setTo(coordinateString2Pos(move, 1));
            }
            // now the only difficulty is to find the piece and its starting position...
            m.setFrom(-1);
            for (ChessPiece p : piecesOnBoard) {
                // check if this piece matches the type and can move there in one hop.
                // TODO!!: it can still take wrong piece that is pinned to its king...
                if (p != null && movingPceType == p.getPieceType()                                    // found Piece p that matches the wanted type
                        && (fromFile == -1 || fileOf(p.getPos()) == fromFile)       // no extra file is specified or it is correct
                        && (fromRank == -1 || rankOf(p.getPos()) == fromRank)       // same for rank
                        && boardSquares[m.to()].getDistanceToPieceId(p.getPieceID()) == 1   // p can move here directly (distance==1)
                        && moveIsNotBlockedByKingPin(p, m.to())                                         // p is not king-pinned or it is pinned but does not move out of the way.
                ) {
                    m.setFrom(p.getPos());
                    break;
                }
            }
            if (!m.isMove())
                return false;  // no matching piece found
        }
        debugPrintln(DEBUGMSG_BOARD_MOVES, "DOMOVE " + squareName(m.from()) + squareName(m.to()) + ". ");
        return doMove(m);  //frompos, topos, promoteToFigNr);
    }

    /**
     * p is not king-pinned or it is pinned but does not move out of the way.
     */
    public boolean moveIsNotBlockedByKingPin(ChessPiece p, int topos){
        if (isKing(p.getPieceType()))
            return true;
        int sameColorKingPos = p.isWhite() ? whiteKingPos : blackKingPos;
        if (sameColorKingPos < 0)
            return true;  // king does not exist... should not happen, but is part of some test-positions
        if (!isPiecePinnedToPos(p, sameColorKingPos))
            return true;   // p is not king-pinned
        if (colorlessPieceType(p.getPieceType()) == KNIGHT)
            return false;  // a king-pinned knight can never move away in a way that it still avoids the check
        // or it is pinned, but does not move out of the way.
        int king2PceDir = calcDirFromTo(sameColorKingPos, topos);
        int king2TargetDir = calcDirFromTo(sameColorKingPos, p.getPos());
        return king2PceDir == king2TargetDir;
        // TODO?:  could also be solved by more intelligent condition stored in the distance to the king
    }


    public boolean isPiecePinnedToPos(ChessPiece p,int pos){
        int pPos = p.getPos();
        List<Integer> listOfSquarePositionsCoveringPos = boardSquares[pos].getPositionsOfPiecesThatBlockWayAndAreOfColor(p.color());
        for (Integer covpos : listOfSquarePositionsCoveringPos)
            if (covpos == pPos)
                return true;
        return false;
    }

    public boolean posIsBlockingCheck(boolean kingcol, int pos){
        return boardSquares[pos].blocksCheckFor(kingcol);
    }

    public int nrOfLegalMovesForPieceOnPos ( int pos){
        ChessPiece sqPce = boardSquares[pos].myPiece();
        if (sqPce == null)
            return -1;
        return sqPce.getLegalMovesAndChances().size();
    }


    int getPieceTypeAt(int pos){
        int pceID = boardSquares[pos].getPieceID();
        if (pceID == NO_PIECE_ID || piecesOnBoard[pceID] == null)
            return EMPTY;
        return piecesOnBoard[pceID].getPieceType();
    }

    private void takePieceAway ( int topos){
        //decreasePieceNrCounter(takenFigNr);
        //updateHash(takenFigNr, topos);
        ChessPiece p = getPieceAt(topos);
        p.startNextUpdate();
        piecesOnBoard[p.getPieceID()] = null;
        if (p.isWhite())
            countOfWhitePieces--;
        else
            countOfBlackPieces--;

        int pceType = p.getPieceType();
        switch (colorlessPieceType(pceType)) {
            case BISHOP -> countBishops[colorIndexOfPieceType(pceType)]--;
            case KNIGHT -> countKnights[colorIndexOfPieceType(pceType)]--;
            case PAWN   -> countPawnsInFile[colorIndexOfPieceType(pceType)][fileOf(topos)]--;
        }

        for (Square s : boardSquares)
            s.removePiece(p.getPieceID());
        p.die();
        emptySquare(topos);
    }

    private void basicMoveFromTo(final int pceType, final int pceID, final int frompos, final int topos){
        if (frompos==topos)
            return;  // this is ok, e.g. in chess960 castling, a rook or king might end up in the exact same square again...
        if (pceType == KING)
            whiteKingPos = topos;
        else if (pceType == KING_BLACK)
            blackKingPos = topos;
        updateHashWithMove(frompos, topos);
        // re-place piece on board
        emptySquare(frompos);
        piecesOnBoard[pceID].setPos(topos);
        // tell the square
        setCurrentDistanceCalcLimit(0);
        boardSquares[topos].movePieceHereFrom(pceID, frompos);
        // tell all Pieces to update their vPieces (to recalc the distances)
        ChessPiece mover = piecesOnBoard[pceID];
        mover.updateDueToPceMove(frompos, topos);
        //continueDistanceCalcUpTo(1);  // to do at lease one round of recalc of relEvals an NoGos
        for (ChessPiece chessPiece : piecesOnBoard)
            if (chessPiece != null && chessPiece != mover)
                chessPiece.updateDueToPceMove(frompos, topos);
        // for Test: "deactivation of recalc eval in doMove-methods in ChessBoard
        //           for manual tests with full Board reconstruction of every position, instead of evolving evaluations per move (just to compare speed)"
        // deactivate the following (correct) code:
        completeCalc();
    /* setCurrentDistanceCalcLimit(0);
    boardSquares[frompos].pieceHasMovedAway();
    completeDistanceCalc();
     */
        // Check: was this forgotten here, but should be called?
        //   no --> it should already be included in the updateDueToPceMove() calls above
        //   boardSquares[frompos].pieceHasMovedAway();
        //   (and if yes, then check if this must not actually be called be before completeCalc()!!

    }

    public boolean isSquareEmpty(final int pos){
        return (boardSquares[pos].isEmpty());
    }

    private void emptySquare(final int frompos){
        boardSquares[frompos].emptySquare();
    }

    private void basicMoveFromTo(final int frompos, final int topos){
        int pceID = getPieceIdAt(frompos);
        int pceType = getPieceTypeAt(frompos);
        basicMoveFromTo(pceType, pceID, frompos, topos);
    }

    public String getPieceFullName(int pceId){
        return getPiece(pceId).toString();
    }

    public int getDistanceToPosFromPieceId(int pos, int pceId) {
        return boardSquares[pos].getDistanceToPieceId(pceId);
    }

    public boolean isDistanceToPosFromPieceIdUnconditional(int pos, int pceId) {
        return boardSquares[pos].getConditionalDistanceToPieceId(pceId).isUnconditional();
    }

    public boolean isWayToPosFromPieceIdNoGo(int pos, int pceId) {
        return boardSquares[pos].getConditionalDistanceToPieceId(pceId).hasNoGo();
    }

    ConditionalDistance getDistanceFromPieceId(int pos, int pceId) {
        return boardSquares[pos].getConditionalDistanceToPieceId(pceId);
    }

    public String getGameState() {
        checkAndEvaluateGameOver();
        String res;
        if (isGameOver()) {
            if (isCheck(WHITE))
                res = chessBasicRes.getString("state.blackWins");
            else if (isCheck(BLACK))
                res = chessBasicRes.getString("state.whiteWins");
            else
                res = chessBasicRes.getString("state.remis");
            //TODO?: check and return stalemate
        }
        else {
            if (getFullMoves() == 0)
                res = chessBasicRes.getString("state.notStarted");
            else
                res = chessBasicRes.getString("state.ongoing");
        }
        if (repetitions>0)
            res += " (" + repetitions + " " + chessBasicRes.getString("repetitions") + ")";
        return res;
    }

    public Iterator<ChessPiece> getPiecesIterator() {
        return Arrays.stream(piecesOnBoard).iterator();
    }

    // virtual non-linear, but continuously increasing "clock" used to remember update-"time"s and check if information is outdated
    private long updateClockFineTicks = 0;

    public int getNrOfPlys () {
        if (isWhite(turn))
            return fullMoves * 2;
        return fullMoves * 2 + 1;
    }

    public long getUpdateClock() {
        return getNrOfPlys() * 10000L + updateClockFineTicks;
    }

    public long nextUpdateClockTick() {
        ++updateClockFineTicks;
        return getUpdateClock();
    }

    public void internalErrorPrintln(String s){
        System.err.println(chessBasicRes.getString("errormessage.errorPrefix") + s);
        System.err.println( "Board: " + getBoardFEN() );
    }


    public static void debugPrint(boolean doPrint, String s){
        if (doPrint)
            System.err.print(s);
    }

    public static void debugPrintln(boolean doPrint, String s){
        if (doPrint)
            System.err.println(s);
    }

/* replaced the following in all files:
... this makes almost the whole sense of the debugPrint obsolete, but avoids string preparations before calling
    Find:
^([^/
]*if(\s*)\()(.*)(\)[^;][\s
]*)(debugPrint(ln)?\(([^,]*),([^;]*)((
).*);)
    Replace:
$1$7 && $3$4$5
---
    Find:
(;
(.*))(debugPrint(ln)?\(([^,]*),([^;]*)((
).*);)
    Replace:
$1if ($5)
$2    $3
---
    Find:
(\{
(.*))(debugPrint(ln)?\(([^,]*),([^;]*)((
).*);)
    Replace:
$1if ($5)
$2    $3

*/

    public int currentDistanceCalcLimit() {
        return currentDistanceCalcLimit;
    }

    private void setCurrentDistanceCalcLimit ( int newLimit){
        currentDistanceCalcLimit = min(MAX_INTERESTING_NROF_HOPS, newLimit);
    }


    @Override
    public boolean equals(Object o){
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ChessBoard other = (ChessBoard) o;
        debugPrint(DEBUGMSG_BOARD_COMPARE_FRESHBOARD, "Comparing Boards: " + this.getBoardName() + " with " + other.getBoardName() + ":  ");

        boolean equal = compareWithDebugMessage("White King Pos", whiteKingPos, other.whiteKingPos);
        equal &= compareWithDebugMessage("Black King Pos", blackKingPos, other.blackKingPos);
        equal &= compareWithDebugMessage("White King Checks", nrOfChecks(WHITE), other.nrOfChecks(WHITE));
        equal &= compareWithDebugMessage("Black King Checks", nrOfChecks(BLACK), other.nrOfChecks(BLACK));
        equal &= compareWithDebugMessage("Count White Pieces", countOfWhitePieces, other.countOfWhitePieces);
        equal &= compareWithDebugMessage("Count Black Pieces", countOfBlackPieces, other.countOfBlackPieces);
        equal &= compareWithDebugMessage("Game Over", gameOver, other.gameOver);
        equal &= compareWithDebugMessage("Turn", turn, other.turn);
        equal &= compareWithDebugMessage("White Kingside Castling Allowed", kingsideCastlingAllowed[CIWHITE], other.kingsideCastlingAllowed[CIWHITE]);
        equal &= compareWithDebugMessage("White Queenside Castling Allowed", queensideCastlingAllowed[CIWHITE], other.queensideCastlingAllowed[CIWHITE]);
        equal &= compareWithDebugMessage("Black Kingside Castling Allowed", kingsideCastlingAllowed[CIBLACK], other.kingsideCastlingAllowed[CIBLACK]);
        equal &= compareWithDebugMessage("Black Queenside Castling Allowed", queensideCastlingAllowed[CIBLACK], other.queensideCastlingAllowed[CIBLACK]);
        equal &= compareWithDebugMessage("EnPassant File", enPassantFile, other.enPassantFile);
        equal &= compareWithDebugMessage("Boring Moves", countBoringMoves, other.countBoringMoves);
        equal &= compareWithDebugMessage("Full Moves", fullMoves, other.fullMoves);
        for (int pos = 0; pos < NR_SQUARES; pos++) {
            int pceId = boardSquares[pos].getPieceID();
            if (pceId != NO_PIECE_ID) {
                // piece found, get id of same piece on other board
                int otherPceId = other.boardSquares[pos].getPieceID();
                // compare all vPieces with this PceID on all squares
                for (int vpos = 0; vpos < NR_SQUARES; vpos++) {
                    VirtualPieceOnSquare thisVPce = boardSquares[vpos].getvPiece(pceId);
                    VirtualPieceOnSquare otherVPce = other.boardSquares[vpos].getvPiece(otherPceId);
                    equal &= thisVPce.equals(otherVPce);
                    //equal &= compareWithDebugMessage(thisVPce+" myPCeId", thisVPce.myPceID, otherVPce.myPceID );
                }
            }
        }
        if (equal) {
            if (DEBUGMSG_BOARD_COMPARE_FRESHBOARD)
                debugPrintln(DEBUGMSG_BOARD_COMPARE_FRESHBOARD, " --> ok");
        } else if (DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL) {
            debugPrintln(DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL, " --> Problem on Board " + this.getBoardFEN());
        }
        return equal;
    }

    static boolean compareWithDebugMessage(String debugMesg,int thisInt, int otherInt){
        boolean cmp = (thisInt == otherInt);
        if (DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL && !cmp)
            debugPrintln(DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL, debugMesg + ": " + thisInt + " != " + otherInt);
        return cmp;
    }

    static boolean compareWithDebugMessage(String debugMesg,boolean thisBoolean, boolean otherBoolean){
        boolean cmp = (thisBoolean == otherBoolean);
        if (DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL && !cmp)
            debugPrintln(DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL, debugMesg + ": " + thisBoolean + " != " + otherBoolean);
        return cmp;
    }

    static boolean compareWithDebugMessage(String debugMesg,
                                           ConditionalDistance thisDistance,
                                           ConditionalDistance otherDistance){
        boolean cmp = (thisDistance.dist() == otherDistance.dist()
                || thisDistance.dist() >= MAX_INTERESTING_NROF_HOPS && otherDistance.dist() >= MAX_INTERESTING_NROF_HOPS);
        if (DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL && !cmp)
            debugPrintln(DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL,
                    debugMesg + ".dist: " + thisDistance
                            + " != " + otherDistance);
        boolean cmp2 = (thisDistance.hasNoGo() == otherDistance.hasNoGo());
        if (DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL && !cmp2)
            debugPrintln(DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL,
                    debugMesg + ".nogo: " + thisDistance
                            + " != " + otherDistance);
        return cmp && cmp2;
    }

    static boolean compareWithDebugMessage(String debugMesg,int[] thisIntArray, int[] otherIntArray){
        boolean cmp = Arrays.equals(thisIntArray, otherIntArray);
        if (DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL && !cmp)
            debugPrintln(DEBUGMSG_BOARD_COMPARE_FRESHBOARD_NONEQUAL, debugMesg + ": " + Arrays.toString(thisIntArray) + " != " + Arrays.toString(otherIntArray));
        return cmp;
    }

    static boolean compareWithDebugMessage(String debugMesg,
                                           ConditionalDistance[]thisDistanceArray,
                                           ConditionalDistance[]otherDistanceArray){
        boolean cmp = true;
        for (int i = 0; i < thisDistanceArray.length; i++)
            cmp &= compareWithDebugMessage(debugMesg + "[" + i + "]",
                    thisDistanceArray[i],
                    otherDistanceArray[i]);
        return cmp;
    }

    //// Hash methods

    // initialize random values once at startup
    static private final long[] randomSquareValues = new long[70];
    static {
        for (int i=0; i<randomSquareValues.length; i++)
            randomSquareValues[i] = (long)(random()*((double)(Long.MAX_VALUE>>1)));
    }

    private void resetHashHistory() {
        boardHashHistory = new ArrayList<List<Long>>(2);  // 2 for the colors
        for (int ci = 0; ci < 2; ci++) {
            boardHashHistory.add( new ArrayList<Long>(50) );  // then ArrayList<>(50) for 50 Hash values:
        }
        repetitions = 0;
    }

    /**
     * returns how many times the position reached by a move has been there before
     */
    int moveLeadsToRepetitionNr(int frompos, int topos) {
        boolean color = opponentColor(getTurnCol());
        long resultingHash = calcBoardHashAfterMove(frompos,topos);
        return countHashOccurrencesForColor(resultingHash, color)+1;
    }

    private int countHashOccurrencesForColor(long resultingHash, boolean color) {
        int ci = colorIndex(color);
        return (int) (boardHashHistory.get(ci).stream()
                .filter(h -> (h.equals(resultingHash)))
                .count());
    }

    public long getBoardHash() {
        long hash= boardHash ^ randomSquareValues[68] * getEnPassantFile();
        if (isQueensideCastleAllowed(BLACK))
            hash ^= randomSquareValues[64];
        if (isKingsideCastleAllowed(BLACK))
            hash ^= randomSquareValues[65];
        if (isQueensideCastleAllowed(WHITE))
            hash ^= randomSquareValues[66];
        if (isKingsideCastleAllowed(WHITE))
            hash ^= randomSquareValues[67];
        /*if (turn)  // we do not need to hash the turn-flag, as we store hashHistroy seperately for both color turns
            hash ^= randomSquareValues[69];
        /*if (countBoringMoves>MAX_BORING_MOVES-3)
            hash ^= randomSquareValues[70]*(MAX_BORING_MOVES-countBoringMoves+1);*/  // move repetitions should lea to same hash value
        return boardHash;
    }

    private void initHash() {
        boardHash = 0;
        for (int p=0; p<64; p++) {
            long f = getPieceTypeAt(p);
            if (f!=NO_PIECE_ID)
                boardHash ^= randomSquareValues[p]*f;
        }
        repetitions = 0;
    }

    public long calcBoardHashAfterMove(int frompos, int topos) {
        // TODO: EnPassant und Rochade sind hier nicht implementiert!
        int fromPceType = getPieceTypeAt(frompos);
        int takenPceType = getPieceTypeAt(topos);
        long hash = rawUpdateAHash( getBoardHash(), fromPceType, frompos);
        if (takenPceType != NO_PIECE_ID)
            hash = rawUpdateAHash( hash, takenPceType, topos);
        hash = rawUpdateAHash( hash, fromPceType, topos);
        return hash;
    }

    private void updateHashWithMove(final int frompos, final int topos) {
        boardHash = calcBoardHashAfterMove(frompos, topos);
        // add Hash To History
        int nextci = colorIndex(opponentColor(getTurnCol()));
        boardHashHistory.get( nextci ).add(getBoardHash());
        repetitions = countHashOccurrencesForColor(boardHash, opponentColor(getTurnCol()) ) - 1;
    }

    public long rawUpdateAHash(long hash, int pceType, int pos) {
        if (((long)pceType) != 0)
            hash ^= randomSquareValues[pos] * ((long)pceType);
        return hash;
    }


    //// getter

    public int getPieceCounter() {
        return countOfWhitePieces + countOfBlackPieces;
    }

    public int getPieceCounterForColor( boolean whitecol){
        return whitecol ? countOfWhitePieces
                : countOfBlackPieces;
    }

    public int getLightPieceCounterForPieceType(int pceType ){
        int ci = colorIndex(colorOfPieceType(pceType));
        if ( colorlessPieceType(pceType) == BISHOP )
            return countBishops[ci];
        //else  == KNIGHT
        return countKnights[ci];
    }

    public int getPawnCounterForColorInFileOfPos(boolean col, int pos ){
        int file = fileOf(pos);
        int ci = colorIndex(col);
        return countPawnsInFile[ci][file];
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public int nrOfLegalMoves(boolean col){
        return nrOfLegalMoves[colorIndex(col)];
    }

    public int getKingPos(boolean col){
        return isWhite(col) ? whiteKingPos : blackKingPos;
    }

    public int getKingId(boolean col) {
        return getPieceIdAt(getKingPos(col));
    }

    public static int getMAX_INTERESTING_NROF_HOPS() {
        return MAX_INTERESTING_NROF_HOPS;
    }

    public boolean getTurnCol() {
        return turn;
    }

    StringBuffer getBoardName() {
        StringBuffer n = new StringBuffer(boardName + "("+engineP1()+")");
        return n;
    }

    StringBuffer getShortBoardName() {
        return boardName;
    }

    @Deprecated
    public Square[] getBoardSquares() {
        return boardSquares;
    }

    /*Stream<Square> getBoardSquaresStream() {
        return Arrays.stream(boardSquares);
    }*/

    public int getNrOfKingAreaAttacks(boolean onKingColor ) {
        return nrOfKingAreaAttacks[colorIndex(onKingColor)][colorIndex(opponentColor(onKingColor))];
    }

    public int getNrOfKingAreaDefends(boolean onKingColor ) {
        return nrOfKingAreaAttacks[colorIndex(onKingColor)][colorIndex(onKingColor)];
    }

    public int getKingSafetyEstimation(boolean onKingColor ) {
        return getNrOfKingAreaDefends(onKingColor) - getNrOfKingAreaAttacks(onKingColor);
    }


    public int getRepetitions() {
        return repetitions;
    }


    //// setter

    public static void setMAX_INTERESTING_NROF_HOPS ( int RECONST_MAX_INTERESTING_NROF_HOPS){
        MAX_INTERESTING_NROF_HOPS = RECONST_MAX_INTERESTING_NROF_HOPS;
    }

    //void setTurn(boolean turn);

    /** "more or less", because moving away from the m2bBlocked-target is also considered hindering here.
     * This e.g. is useful, if the target is the king and moving out of the way is needed to be seen as "hindering" the check.
     * @param m
     * @param m2bBlocked
     * @return
     */
    private boolean moveIsMoreOrLessHinderingMove(EvaluatedMove m, EvaluatedMove m2bBlocked) {
        if (m.to() == m2bBlocked.from())
            return true;
        if (m.from() == m2bBlocked.to()
            && !( isPawn((getBoardSquare(m.from()).myPiece().getPieceType()) )  // pawn moving away does not protect the left behind square
                  && abs(m2bBlocked.getEvalAt(0)) > (positivePieceBaseValue(PAWN)+EVAL_HALFAPAWN) )  // but benefit was more or less just the pawn
        ) {
            return true;
            //todo: not really hindering, just reducing it's effect - but this cannot be returned for now. (this is one reason, why mate detection at move selection can not work for now
            //note: except for pawns trying to take a (here moving away) piece, which is never possible, so then true is always correct
        }
        if (isBetweenFromAndTo(m.to(), m2bBlocked.from(), m2bBlocked.to()))
            return true;
        return false;
    }

    /** like moveIsMoreOrLessHinderingMove(), but false for m moving out of the way of the target - unless this really
     * hinders the move (i.e. a taking pawn move can be hindered by moving out of the way)
     *
     * @param m
     * @param m2bBlocked
     * @return
     */
    private boolean moveIsReallyHinderingMove(EvaluatedMove m, EvaluatedMove m2bBlocked) {
        if (m.to() == m2bBlocked.from())
            return true;
        if ( m.from() == m2bBlocked.to()
                && isPawn((getBoardSquare(m2bBlocked.from()).myPiece().getPieceType()) )
                && !isSquareEmpty(m2bBlocked.to()) ) {
            return true; // pawn tries to take, but target moves away, so m hinders the pawn move
        }
        if (isBetweenFromAndTo(m.to(), m2bBlocked.from(), m2bBlocked.to()))
            return true;
        return false;
    }

    /** if called when it is clear that !moveIsReallyHinderingMove(m, m2bBlocked) it returns full -eval[0] of b2bBlocked
     *
     * @param m
     * @param m2bBlocked
     * @return assumed eval[0] change effect on eval of m2bBlocked (to be added for the resulting eval)
     */
    private int evalEffectOfMovingAwayAgainst(EvaluatedMove m, EvaluatedMove m2bBlocked) {
        int eval0 = m2bBlocked.getEvalAt(0);
        if (m.to() == m2bBlocked.from())
            return -eval0;
        ChessPiece mPce = getBoardSquare(m.from()).myPiece();
        ChessPiece m2bbPce = getBoardSquare(m2bBlocked.from()).myPiece();
        if ( m.from() == m2bBlocked.to() ) {
            //here, not yet really clear if moving away from the to-square is hindering the move
            // but one case is clear: pawns trying to take a (here moving away) piece, is never possible
            if ( isPawn((m2bbPce.getPieceType()) )
                    && !isSquareEmpty(m2bBlocked.to()) )
                return -eval0;
            if (isBetweenFromAndTo(m.to(), m2bBlocked.from(), m2bBlocked.to()))
                return -eval0;
            // from here it is the situation, that moving away with m does not hinder m2bBlocked, but reduces it's effect
            if ( !moveIsCoveringMoveTarget(m,m2bBlocked) )
                return mPce.getValue();  // e.g. a pawn does not protect backwards, so the opponent keeps all benefits except taking this pawn
            //here now not only saving the mPce, but also covering backwards.
            return mPce.getValue() - (int)(m2bbPce.getValue() * 0.75);  // *0.75 because we do not really know. should calc out the new clashEval here
        }
        if (isBetweenFromAndTo(m.to(), m2bBlocked.from(), m2bBlocked.to()))
            return -eval0;
        return 0;
    }


    private boolean moveIsCoveringMoveTarget(EvaluatedMove move, EvaluatedMove oppMove) {
        // todo!: this test is probably too optimistic, simply covering a square does not mean it is covered strong enough.
        int mId = getBoardSquare(move.from()).myPiece().getPieceID();
        VirtualPieceOnSquare mVPceAtOppToPos = getBoardSquare(oppMove.to()).getvPiece(mId);
        if (mVPceAtOppToPos.getRawMinDistanceFromPiece().dist()>2)  // cannot be reached
            return false;
        VirtualPieceOnSquare mVPceAtMToPos = getBoardSquare(move.to()).getvPiece(mId);
        // it directly covers the square after move
        if ( mVPceAtOppToPos.getDirectAttackVPcs().contains(mVPceAtMToPos) ) //getShortestReasonableUnconditionedPredecessors().contains( mVPceAtMToPos ) )
            return true;
        // it is already there, move away and covers backwards - unless it is a pawn which cannot... (unless it will promote after its move)
        if ( mVPceAtOppToPos.getRawMinDistanceFromPiece().dist()==0
                && !(isPawn(mVPceAtOppToPos.getPieceType())
                     && !isPromotionRankForColor(move.to(), mVPceAtMToPos.color()) ) )
            return true;
        return false;
    }

    Square getBoardSquare(int pos) {
        return boardSquares[pos];
    }



}