package com.boydti.fawe.wrappers;

import com.sk89q.worldedit.*;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.internal.LocalWorldAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;

public class LocationMaskedPlayerWrapper extends PlayerWrapper {
    private Location position;

    public LocationMaskedPlayerWrapper(Player parent, Location position) {
        super(parent);
        this.position = position;
    }

    @Override
    public double getYaw() {
        return position.getYaw();
    }

    @Override
    public double getPitch() {
        return position.getPitch();
    }

    @Override
    public WorldVector getBlockIn() {
        WorldVector pos = getPosition();
        return WorldVector.toBlockPoint(pos.getWorld(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public WorldVector getBlockOn() {
        WorldVector pos = getPosition();
        return WorldVector.toBlockPoint(pos.getWorld(), pos.getX(), pos.getY() - 1, pos.getZ());
    }

    public void setPosition(Location position) {
        this.position = position;
    }

    @Override
    public WorldVector getPosition() {
        LocalWorld world;
        if (position.getExtent() instanceof LocalWorld) {
            world = (LocalWorld) position.getExtent();
        } else {
            world = LocalWorldAdapter.adapt((World) position.getExtent());
        }
        return new WorldVector(world, position.toVector());
    }

    @Override
    public Location getLocation() {
        return position;
    }

    @Override
    public void setPosition(Vector pos, float pitch, float yaw) {
        this.position = new Location(position.getExtent(), pos, pitch, yaw);
    }
}
