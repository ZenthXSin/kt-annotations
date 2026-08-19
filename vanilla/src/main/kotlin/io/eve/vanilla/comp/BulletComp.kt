package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.func.Cons
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Drawf
import arc.math.Angles
import arc.math.Mathf
import arc.math.geom.Geometry
import arc.math.geom.Posc
import arc.math.geom.Rotc
import arc.math.geom.Vec2
import arc.struct.IntSeq
import arc.util.Time
import arc.util.Tmp
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.core.World
import mindustry.entities.EntityCollisions
import mindustry.entities.Mover
import mindustry.entities.bullet.BulletType
import mindustry.game.Team
import mindustry.gen.*
import mindustry.graphics.Trail
import mindustry.logic.LAccess
import mindustry.logic.Senseable
import mindustry.logic.Settable
import mindustry.world.Tile
import mindustry.world.blocks.environment.StaticWall

@Component
abstract class BulletComp : Timedc, Damagec, Hitboxc, Teamc, Posc, Drawc, Shielderc, Ownerc, Bulletc, Timerc, Senseable, Settable {
    @Import var team = Team.derelict
    @Import var owner: Entityc? = null
    @Import var x = 0f
    @Import var y = 0f
    @Import var damage = 0f
    @Import var lastX = 0f
    @Import var lastY = 0f
    @Import var time = 0f
    @Import var lifetime = 0f

    var collided = IntSeq(6)
    var type: BulletType? = null
    var vel = Vec2()

    var data: Any? = null
    var fdata = 0f

    @ReadOnly
    private var rotation = 0f

    @Transient var keepAlive = false
    @Transient var justSpawned = true
    @Transient var shooter: Entityc? = null
    @Transient var aimTile: Tile? = null
    @Transient var aimX = 0f
    @Transient var aimY = 0f
    @Transient var originX = 0f
    @Transient var originY = 0f
    @Transient var buildingDamageMultiplier = 0f
    @Transient var mover: Mover? = null
    @Transient var absorbed = false
    @Transient var hit = false
    @Transient var trail: Trail? = null
    @Transient var frags = 0

    @Transient var stickyTarget: Posc? = null
    @Transient var stickyX = 0f
    @Transient var stickyY = 0f
    @Transient var stickyRotation = 0f
    @Transient var stickyRotationOffset = 0f

    fun getCollisions(consumer: Cons<in QuadTree<*>>) {
        val data = Vars.state.teams.present
        for (i in 0 until data.size) {
            if (data.items[i].team != team) {
                consumer.get(data.items[i].tree())
            }
        }
    }

    @Replace
    override fun isLocal(): Boolean = true

    override fun add() {
        type!!.init(self())
    }

    override fun remove() {
        if (Groups.isClearing) return
        if (!hit) {
            type!!.despawned(self())
        }
        type!!.removed(self())
        collided.clear()
    }

    override fun damageMultiplier(): Float = type!!.damageMultiplier(self())

    override fun absorb() {
        absorbed = true
        remove()
    }

    fun hasCollided(id: Int): Boolean = collided.size != 0 && collided.contains(id)

    @Replace
    fun clipSize(): Float = type!!.drawSize

    @Replace
    override fun collides(other: Hitboxc): Boolean {
        return type!!.collides && (other is Teamc && other.team() != team)
            && !(other is Unit && !other.checkTarget(type!!.collidesAir, type!!.collidesGround))
            && !(type!!.pierce && hasCollided(other.id())) && stickyTarget == null
    }

    @MethodPriority(100)
    override fun collision(other: Hitboxc, x: Float, y: Float) {
        if (type!!.sticky) {
            if (stickyTarget == null) {
                this.x = x + vel.x
                this.y = y + vel.y
                stickTo(other)
            }
        } else {
            type!!.hit(self(), x, y)
            if (!type!!.pierce) {
                hit = true
                remove()
            } else {
                collided.add(other.id())
            }
            type!!.hitEntity(self(), other, if (other is Healthc) other.health() else 0f)
        }
    }

    fun stickTo(other: Posc) {
        lifetime += type!!.stickyExtraLifetime
        stickyX = this.x - other.x()
        stickyY = this.y - other.y()
        stickyTarget = other
        stickyRotationOffset = rotation
        stickyRotation = if (other is Rotc) other.rotation() else 0f
    }

    override fun update() {
        if (!justSpawned) {
            x += vel.x * Time.delta
            y += vel.y * Time.delta
            vel.scl(Math.max(1f - type!!.drag * Time.delta, 0))
        }
        justSpawned = false

        if (mover != null) {
            mover!!.move(self())
        }

        if (type!!.accel != 0f) {
            vel.setLength(vel.len() + type!!.accel * Time.delta)
        }

        type!!.update(self())

        if (stickyTarget != null) {
            if (stickyTarget is Healthc && (stickyTarget as Healthc).isValid()) {
                val rotate = if (stickyTarget is Rotc) (stickyTarget as Rotc).rotation() - stickyRotation else 0f
                set(Tmp.v1.set(stickyX, stickyY).rotate(rotate).add(stickyTarget!!))
                this.rotation = rotate + stickyRotationOffset
                vel.setAngle(this.rotation)
            }
        } else if (type!!.collidesTiles && type!!.collides && type!!.collidesGround) {
            tileRaycast(World.toTile(lastX), World.toTile(lastY), tileX(), tileY())
        }

        if (type!!.removeAfterPierce && type!!.pierceCap != -1 && collided.size >= type!!.pierceCap) {
            hit = true
            remove()
        }

        if (keepAlive) {
            time -= Time.delta
            keepAlive = false
        }
    }

    fun moveRelative(x: Float, y: Float) {
        val rot = rotation()
        this.x += Angles.trnsx(rot, x * Time.delta, y * Time.delta)
        this.y += Angles.trnsy(rot, x * Time.delta, y * Time.delta)
    }

    fun turn(x: Float, y: Float) {
        val ang = vel.angle()
        vel.add(Angles.trnsx(ang, x * Time.delta, y * Time.delta), Angles.trnsy(ang, x * Time.delta, y * Time.delta)).limit(type!!.speed)
    }

    fun checkUnderBuild(build: Building, x: Float, y: Float): Boolean {
        return (!build.block.underBullets ||
            (aimTile != null && aimTile!!.build == build) ||
            type!!.hitUnder ||
            (build.team == team) ||
            (type!!.pierce && aimTile != null && Mathf.dst(x, y, originX, originY) > aimTile!!.dst(originX, originY) + 2f) ||
            (aimX == -1f && aimY == -1f))
    }

    override fun tileRaycast(x1: Int, y1: Int, x2: Int, y2: Int) {
        var x = x1
        var y = y1
        val dx = Math.abs(x2 - x)
        val sx = if (x < x2) 1 else -1
        val dy = Math.abs(y2 - y)
        val sy = if (y < y2) 1 else -1
        var err = dx - dy
        val ww = Vars.world.width()
        val wh = Vars.world.height()

        while (x >= 0 && y >= 0 && x < ww && y < wh) {
            val build = Vars.world.build(x, y)

            if (type!!.collideFloor || type!!.collideTerrain) {
                val tile = Vars.world.tile(x, y)
                if (type!!.collideFloor && (tile == null || tile.floor().hasSurface() || tile.block() != Blocks.air) ||
                    type!!.collideTerrain && tile != null && tile.block() is StaticWall
                ) {
                    remove()
                    hit = true
                    return
                }
            }

            if (build != null && isAdded()
                && checkUnderBuild(build, x * Vars.tilesize, y * Vars.tilesize)
                && build.collide(self()) && type!!.testCollision(self(), build)
                && !build.dead() && (type!!.collidesTeam || build.team != team) && !(type!!.pierceBuilding && hasCollided(build.id))
            ) {
                if (type!!.sticky) {
                    if (build.team != team) {
                        val hit = Geometry.raycastRect(lastX, lastY, x.toFloat(), y.toFloat(),
                            Tmp.r1.setCentered(x * Vars.tilesize, y * Vars.tilesize, Vars.tilesize, Vars.tilesize))
                        if (hit != null) {
                            this.x = hit.x
                            this.y = hit.y
                        }
                        stickTo(build)
                        return
                    }
                } else {
                    var remove = false
                    var doRemove = false
                    val health = build.health

                    if (build.team != team) {
                        remove = build.collision(self())
                    }

                    if (remove || type!!.collidesTeam) {
                        if (Mathf.dst2(lastX, lastY, x * Vars.tilesize, y * Vars.tilesize) < Mathf.dst2(lastX, lastY, this.x, this.y)) {
                            this.x = x * Vars.tilesize
                            this.y = y * Vars.tilesize
                        }
                        if (!type!!.pierceBuilding) {
                            hit = true
                            doRemove = true
                        } else {
                            collided.add(build.id)
                        }
                    }

                    type!!.hitTile(self(), build, x * Vars.tilesize, y * Vars.tilesize, health, true)
                    if (doRemove) {
                        remove()
                    }
                    if (type!!.pierceBuilding) return
                }
            }

            if (x == x2 && y == y2) break

            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x += sx
            }
            if (e2 < dx) {
                err += dx
                y += sy
            }
        }
    }

    override fun draw() {
        Draw.z(type!!.layer)
        if (type!!.underwater) {
            Drawf.underwater { type!!.draw(self()) }
        } else {
            type!!.draw(self())
        }
        type!!.drawLight(self())
        Draw.reset()
    }

    fun initVel(angle: Float, amount: Float) {
        vel.trns(angle, amount)
        rotation = angle
    }

    override fun rotation(angle: Float) {
        rotation = angle
        vel.setAngle(angle)
    }

    override fun rotation(): Float = if (vel.isZero(0.001f)) rotation else vel.angle()

    override fun sense(sensor: LAccess): Double = when (sensor) {
        LAccess.rotation -> rotation.toDouble()
        LAccess.health -> damage.toDouble()
        LAccess.maxHealth -> type!!.damage.toDouble()
        LAccess.x -> World.conv(x).toDouble()
        LAccess.y -> World.conv(y).toDouble()
        LAccess.velocityX -> (vel.x * 60f / Vars.tilesize).toDouble()
        LAccess.velocityY -> (vel.y * 60f / Vars.tilesize).toDouble()
        LAccess.dead -> if (!isAdded()) 1.0 else 0.0
        LAccess.team -> team.id.toDouble()
        LAccess.range -> type!!.range.toDouble()
        LAccess.shootX -> World.conv(aimX()).toDouble()
        LAccess.shootY -> World.conv(aimY()).toDouble()
        LAccess.speed -> (type!!.speed * 60f / Vars.tilesize).toDouble()
        LAccess.size -> (type!!.hitSize / Vars.tilesize).toDouble()
        LAccess.color -> Color.toDoubleBits(team.color.r, team.color.g, team.color.b, 1f)
        LAccess.bulletLifetime -> lifetime.toDouble()
        LAccess.bulletTime -> time.toDouble()
        else -> Double.NaN
    }

    override fun setProp(prop: LAccess, value: Double) = when (prop) {
        LAccess.health -> damage = value.toFloat()
        LAccess.x -> x = World.unconv(value.toFloat())
        LAccess.y -> y = World.unconv(value.toFloat())
        LAccess.velocityX -> vel.x = (value * Vars.tilesize / 60.0).toFloat()
        LAccess.velocityY -> vel.y = (value * Vars.tilesize / 60.0).toFloat()
        LAccess.rotation -> rotation = value.toFloat()
        LAccess.team -> this.team = Team.get(value.toInt())
        LAccess.speed -> vel.setLength(value.toFloat() * 8f)
        LAccess.bulletLifetime -> this.lifetime = value.toFloat()
        LAccess.bulletTime -> this.time = value.toFloat()
        else -> {}
    }

    override fun setProp(content: UnlockableContent, value: Double) {}

    override fun setProp(prop: LAccess, value: Any?) = when (prop) {
        LAccess.team -> {
            if (value is Team) {
                team = value
            }
        }
        else -> {}
    }
}