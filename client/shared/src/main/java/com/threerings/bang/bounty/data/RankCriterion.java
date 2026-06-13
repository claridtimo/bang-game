//
// $Id$

package com.threerings.bang.bounty.data;

import java.io.IOException;
import java.util.HashSet;

import com.jme3.export.InputCapsule;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.OutputCapsule;

import com.threerings.bang.data.StatType;

import com.threerings.bang.game.data.BangObject;
import com.threerings.bang.game.data.Criterion;

/**
 * Requires that the player come in a certain rank.
 */
public class RankCriterion extends Criterion
{
    /** The rank that the player must achieve. */
    public int rank;

    // from Criterion
    public String getDescription ()
    {
        return "m.rank_descrip" + rank;
    }

    // from Criterion
    public void addWatchedStats (HashSet<StatType> stats)
    {
        // nada
    }

    // from Criterion
    public String getCurrentValue (BangObject bangobj, int rank)
    {
        return "m.rank_at" + rank;
    }

    // from Criterion
    public boolean isMet (BangObject bangobj, int rank)
    {
        return this.rank >= rank;
    }

    // from interface Savable
    public void write (JmeExporter ex) throws IOException
    {
        OutputCapsule out = ex.getCapsule(this);
        out.write(rank, "rank", 0);
    }

    // from interface Savable
    public void read (JmeImporter im) throws IOException
    {
        InputCapsule in = im.getCapsule(this);
        rank = in.readInt("rank", 0);
    }

    // from interface Savable
    public Class<?> getClassTag ()
    {
        return getClass();
    }

    @Override // from Object
    public boolean equals (Object other)
    {
        return rank == ((RankCriterion)other).rank;
    }

    @Override // from Object
    public String toString ()
    {
        return "rank >= " + rank;
    }
}
