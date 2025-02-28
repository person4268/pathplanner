package com.pathplanner.lib;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.trajectory.Trajectory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PathPlannerTrajectory extends Trajectory {
    private final List<EventMarker> markers;
    private final double endWaitTime;

    public PathPlannerTrajectory() {
        super();
        this.markers = new ArrayList<>();
        this.endWaitTime = 0;
    }

    protected PathPlannerTrajectory(ArrayList<Waypoint> pathPoints, ArrayList<EventMarker> markers, PathConstraints constraints, boolean reversed){
        super(generatePath(pathPoints, constraints.maxVelocity, constraints.maxAcceleration, reversed));

        this.markers = markers;
        this.calculateMarkerTimes(pathPoints);
        this.endWaitTime = pathPoints.get(pathPoints.size() - 1).waitTime;
    }

    /**
     * Get the end wait time for this path configured in the GUI
     * @return End wait time in seconds
     */
    public double getEndWaitTimeSeconds(){
        return this.endWaitTime;
    }

    /**
     * Get all of the markers in the path
     * @return List of the markers
     */
    public List<EventMarker> getMarkers(){
        return this.markers;
    }

    /**
     * Sample the path at a point in time
     * @param time The time to sample
     * @return The state at the given point in time
     */
    @Override
    public State sample(double time){
        if(time <= getInitialState().timeSeconds) return getInitialState();
        if(time >= getTotalTimeSeconds()) return getEndState();

        int low = 1;
        int high = getStates().size() - 1;

        while(low != high) {
            int mid = (low + high) / 2;
            if(getState(mid).timeSeconds < time){
                low = mid + 1;
            }else{
                high = mid;
            }
        }

        PathPlannerState sample = getState(low);
        PathPlannerState prevSample = getState(low - 1);

        if(Math.abs(sample.timeSeconds - prevSample.timeSeconds) < 1E-3) return sample;

        return prevSample.interpolate(sample, (time - prevSample.timeSeconds) / (sample.timeSeconds - prevSample.timeSeconds));
    }

    /**
     * Get the initial state of the path
     * @return The first state in the path
     */
    public PathPlannerState getInitialState(){
        return (PathPlannerState) getStates().get(0);
    }

    /**
     * Get the initial pose of a holonomic drive robot in the path
     * @return The initial pose
     */
    public Pose2d getInitialHolonomicPose(){
        return new Pose2d(getInitialPose().getTranslation(), getInitialState().holonomicRotation);
    }

    /**
     * Get the end state of the path
     * @return The last state in the path
     */
    public PathPlannerState getEndState(){
        return (PathPlannerState) getStates().get(getStates().size() - 1);
    }

    /**
     * Get a state in the path based on its index.
     * In most cases, using sample() is a better method.
     * @param i The index of the state to retrieve
     * @return The state at the given index
     */
    public PathPlannerState getState(int i) {
        return (PathPlannerState) getStates().get(i);
    }

    private static List<State> generatePath(ArrayList<Waypoint> pathPoints, double maxVel, double maxAccel, boolean reversed){
        ArrayList<ArrayList<Waypoint>> splitPaths = new ArrayList<>();
        ArrayList<Waypoint> currentPath = new ArrayList<>();

        for(int i = 0; i < pathPoints.size(); i++){
            Waypoint w = pathPoints.get(i);

            currentPath.add(w);

            if((i != 0 && w.isReversal) || i == pathPoints.size() - 1){
                splitPaths.add(currentPath);
                currentPath = new ArrayList<>();
                currentPath.add(w);
            }
        }

        ArrayList<ArrayList<PathPlannerState>> splitStates = new ArrayList<>();
        boolean shouldReverse = reversed;
        for(int i = 0; i < splitPaths.size(); i++){
            ArrayList<PathPlannerState> joined = joinSplines(splitPaths.get(i), maxVel, PathPlanner.resolution);
            calculateMaxVel(joined, maxVel, maxAccel, shouldReverse);
            calculateVelocity(joined, splitPaths.get(i), maxAccel);
            recalculateValues(joined, shouldReverse);
            splitStates.add(joined);
            shouldReverse = !shouldReverse;
        }

        ArrayList<Trajectory.State> joinedStates = new ArrayList<>();

        for(int i = 0; i < splitStates.size(); i++){
            if (i != 0){
                double lastEndTime = joinedStates.get(joinedStates.size() - 1).timeSeconds;

                for(Trajectory.State s : splitStates.get(i)){
                    s.timeSeconds += lastEndTime;
                }
            }

            joinedStates.addAll(splitStates.get(i));
        }

        return joinedStates;
    }

    private static void calculateMaxVel(List<PathPlannerState> states, double maxVel, double maxAccel, boolean reversed){
        for(int i = 0; i < states.size(); i++){
            double radius;
            if(i == states.size() - 1){
                radius = calculateRadius(states.get(i - 2), states.get(i - 1), states.get(i));
            }else if(i == 0){
                radius = calculateRadius(states.get(i), states.get(i + 1), states.get(i + 2));
            }else{
                radius = calculateRadius(states.get(i - 1), states.get(i), states.get(i + 1));
            }

            if(reversed){
                radius *= -1;
            }

            if(!Double.isFinite(radius) || Double.isNaN(radius)){
                states.get(i).velocityMetersPerSecond = Math.min(maxVel, states.get(i).velocityMetersPerSecond);
            }else{
                states.get(i).curveRadius = radius;

                double maxVCurve = Math.sqrt(maxAccel * Math.abs(radius));

                states.get(i).velocityMetersPerSecond = Math.min(maxVCurve, states.get(i).velocityMetersPerSecond);
            }
        }
    }

    private static void calculateVelocity(List<PathPlannerState> states, ArrayList<Waypoint> pathPoints, double maxAccel){
        if(pathPoints.get(0).velOverride == -1){
            states.get(0).velocityMetersPerSecond = 0;
        }

        for(int i = 1; i < states.size(); i++){
            double v0 = states.get(i - 1).velocityMetersPerSecond;
            double deltaPos = states.get(i).deltaPos;

            if(deltaPos > 0) {
                double vMax = Math.sqrt(Math.abs(Math.pow(v0, 2) + (2 * maxAccel * deltaPos)));
                states.get(i).velocityMetersPerSecond = Math.min(vMax, states.get(i).velocityMetersPerSecond);
            }else{
                states.get(i).velocityMetersPerSecond = states.get(i - 1).velocityMetersPerSecond;
            }
        }

        if(pathPoints.get(pathPoints.size() - 1).velOverride == -1){
            states.get(states.size() - 1).velocityMetersPerSecond = 0;
        }
        for(int i = states.size() - 2; i > 1; i--){
            double v0 = states.get(i + 1).velocityMetersPerSecond;
            double deltaPos = states.get(i + 1).deltaPos;

            double vMax = Math.sqrt(Math.abs(v0 * v0 + 2 * maxAccel * deltaPos));
            states.get(i).velocityMetersPerSecond = Math.min(vMax, states.get(i).velocityMetersPerSecond);
        }

        double time = 0;
        for(int i = 1; i < states.size(); i++){
            double v = states.get(i).velocityMetersPerSecond;
            double deltaPos = states.get(i).deltaPos;
            double v0 = states.get(i - 1).velocityMetersPerSecond;

            time += (2 * deltaPos) / (v + v0);
            states.get(i).timeSeconds = time;

            double dv = v - v0;
            double dt = time - states.get(i - 1).timeSeconds;

            if(dt == 0){
                states.get(i).accelerationMetersPerSecondSq = 0;
            }else{
                states.get(i).accelerationMetersPerSecondSq = dv / dt;
            }
        }
    }

    private static void recalculateValues(List<PathPlannerState> states, boolean reversed){
        for(int i = states.size() - 1; i >= 0; i--){
            PathPlannerState now = states.get(i);

            if(i != states.size() - 1){
                PathPlannerState next = states.get(i + 1);

                double dt = next.timeSeconds - now.timeSeconds;

                now.angularVelocityRadPerSec = MathUtil.inputModulus(next.poseMeters.getRotation().getRadians() - now.poseMeters.getRotation().getRadians(), -Math.PI, Math.PI) / dt;
                now.holonomicAngularVelocityRadPerSec = MathUtil.inputModulus(next.holonomicRotation.getRadians() - now.holonomicRotation.getRadians(), -Math.PI, Math.PI) / dt;
            }

            if(Double.isInfinite(now.curveRadius) || Double.isNaN(now.curveRadius) || now.curveRadius == 0){
                now.curvatureRadPerMeter = 0;
            }else{
                now.curvatureRadPerMeter = 1 / now.curveRadius;
            }

            if(reversed){
                now.velocityMetersPerSecond *= -1;
                now.accelerationMetersPerSecondSq *= -1;

                double h = now.poseMeters.getRotation().getDegrees() + 180;
                h = MathUtil.inputModulus(h, -180, 180);
                now.poseMeters = new Pose2d(now.poseMeters.getTranslation(), Rotation2d.fromDegrees(h));
            }
        }
    }

    private static ArrayList<PathPlannerState> joinSplines(ArrayList<Waypoint> pathPoints, double maxVel, double step){
        ArrayList<PathPlannerState> states = new ArrayList<>();
        int numSplines = pathPoints.size() - 1;

        for(int i = 0; i < numSplines; i++){
            Waypoint startPoint = pathPoints.get(i);
            Waypoint endPoint = pathPoints.get(i + 1);

            double endStep = (i == numSplines - 1) ? 1.0 : 1.0 - step;
            for(double t = 0; t <= endStep; t += step){
                Translation2d p = GeometryUtil.cubicLerp(startPoint.anchorPoint, startPoint.nextControl, endPoint.prevControl, endPoint.anchorPoint, t);

                PathPlannerState state = new PathPlannerState();
                state.poseMeters = new Pose2d(p, state.poseMeters.getRotation());

                Rotation2d startRot = startPoint.holonomicRotation;
                Rotation2d endRot = endPoint.holonomicRotation;

                int startSeachOffset = 0;
                int endSearchOffset = 0;

                while(startRot == null || endRot == null){
                    if(startRot == null){
                        startSeachOffset++;
                        startRot = pathPoints.get(i - startSeachOffset).holonomicRotation;
                    }
                    if(endRot == null){
                        endSearchOffset++;
                        endRot = pathPoints.get(i + 1 + endSearchOffset).holonomicRotation;
                    }
                }

                double deltaRot = endRot.minus(startRot).getDegrees();
                deltaRot = MathUtil.inputModulus(deltaRot, -180, 180);

                int startRotIndex = i - startSeachOffset;
                int endRotIndex = i + 1 + endSearchOffset;
                int rotRange = endRotIndex - startRotIndex;

                double holonomicRot = GeometryUtil.cosineInterpolate(startRot, Rotation2d.fromDegrees(startRot.getDegrees() + deltaRot), ((i + t) - startRotIndex) / rotRange).getDegrees();
                holonomicRot = MathUtil.inputModulus(holonomicRot, -180, 180);
                state.holonomicRotation = Rotation2d.fromDegrees(holonomicRot);

                if(i > 0 || t > 0){
                    PathPlannerState s1 = states.get(states.size() - 1);
                    PathPlannerState s2 = state;
                    double hypot = s1.poseMeters.getTranslation().getDistance(s2.poseMeters.getTranslation());
                    state.deltaPos = hypot;

                    double heading = Math.toDegrees(Math.atan2(s1.poseMeters.getY() - s2.poseMeters.getY(), s1.poseMeters.getX() - s2.poseMeters.getX())) + 180;
                    heading = MathUtil.inputModulus(heading, -180, 180);
                    state.poseMeters = new Pose2d(state.poseMeters.getTranslation(), Rotation2d.fromDegrees(heading));

                    if(i == 0 && t == step){
                        states.get(states.size() - 1).poseMeters = new Pose2d(states.get(states.size() - 1).poseMeters.getTranslation(), Rotation2d.fromDegrees(heading));
                    }
                }

                if(t == 0.0){
                    state.velocityMetersPerSecond = startPoint.velOverride;
                }else if(t >= 1.0){
                    state.velocityMetersPerSecond = endPoint.velOverride;
                }else {
                    state.velocityMetersPerSecond = maxVel;
                }

                if(state.velocityMetersPerSecond == -1) state.velocityMetersPerSecond = maxVel;

                states.add(state);
            }
        }
        return states;
    }

    private static double calculateRadius(PathPlannerState s0, PathPlannerState s1, PathPlannerState s2){
        Translation2d a = s0.poseMeters.getTranslation();
        Translation2d b = s1.poseMeters.getTranslation();
        Translation2d c = s2.poseMeters.getTranslation();

        Translation2d vba = a.minus(b);
        Translation2d vbc = c.minus(b);
        double cross_z = (vba.getX() * vbc.getY()) - (vba.getY() * vbc.getX());
        double sign = (cross_z < 0) ? 1 : -1;

        double ab = a.getDistance(b);
        double bc = b.getDistance(c);
        double ac = a.getDistance(c);

        double p = (ab + bc + ac) / 2;
        double area = Math.sqrt(Math.abs(p * (p - ab) * (p - bc) * (p - ac)));
        return sign * (ab * bc * ac) / (4 * area);
    }

    /** Assumes states have already been generated and the markers list has been populated */
    private void calculateMarkerTimes(ArrayList<Waypoint> waypoints){
        for(EventMarker marker : this.markers){
            int startIndex = (int) marker.waypointRelativePos;
            double t = marker.waypointRelativePos % 1;

            if(startIndex == waypoints.size() - 1){
                startIndex--;
                t = 1;
            }

            Waypoint startPoint = waypoints.get(startIndex);
            Waypoint endPoint = waypoints.get(startIndex + 1);

            marker.positionMeters = GeometryUtil.cubicLerp(startPoint.anchorPoint, startPoint.nextControl, endPoint.prevControl, endPoint.anchorPoint, t);

            int statesPerWaypoint = (int) (1 / PathPlanner.resolution);
            startIndex = (int) ((statesPerWaypoint * marker.waypointRelativePos) - Math.floor(marker.waypointRelativePos));
            t = (statesPerWaypoint * marker.waypointRelativePos) % 1;

            if(startIndex == getStates().size() - 1){
                startIndex--;
                t = 1;
            }

            double start = getState(startIndex).timeSeconds;
            double end = getState(startIndex + 1).timeSeconds;

            marker.timeSeconds = GeometryUtil.doubleLerp(start, end, t);
        }

        // Ensure the markers are sorted by time
        this.markers.sort(Comparator.comparingDouble(m -> m.timeSeconds));
    }

    public static class PathPlannerState extends State{
        public double angularVelocityRadPerSec = 0;
        public Rotation2d holonomicRotation = new Rotation2d();
        public double holonomicAngularVelocityRadPerSec = 0;

        private double curveRadius = 0;
        private double deltaPos = 0;

        private PathPlannerState interpolate(PathPlannerState endVal, double t){
            PathPlannerState lerpedState = new PathPlannerState();

            lerpedState.timeSeconds = GeometryUtil.doubleLerp(timeSeconds, endVal.timeSeconds, t);
            double deltaT = lerpedState.timeSeconds - timeSeconds;

            if(deltaT < 0){
                return endVal.interpolate(this, 1 - t);
            }

            lerpedState.velocityMetersPerSecond = GeometryUtil.doubleLerp(velocityMetersPerSecond, endVal.velocityMetersPerSecond, t);
            lerpedState.accelerationMetersPerSecondSq = GeometryUtil.doubleLerp(accelerationMetersPerSecondSq, endVal.accelerationMetersPerSecondSq, t);
            Translation2d newTrans = GeometryUtil.translationLerp(poseMeters.getTranslation(), endVal.poseMeters.getTranslation(), t);
            Rotation2d newHeading = GeometryUtil.rotationLerp(poseMeters.getRotation(), endVal.poseMeters.getRotation(), t);
            lerpedState.poseMeters = new Pose2d(newTrans, newHeading);
            lerpedState.angularVelocityRadPerSec = GeometryUtil.doubleLerp(angularVelocityRadPerSec, endVal.angularVelocityRadPerSec, t);
            lerpedState.holonomicAngularVelocityRadPerSec = GeometryUtil.doubleLerp(holonomicAngularVelocityRadPerSec, endVal.holonomicAngularVelocityRadPerSec, t);
            lerpedState.holonomicRotation = GeometryUtil.rotationLerp(holonomicRotation, endVal.holonomicRotation, t);
            lerpedState.curveRadius = GeometryUtil.doubleLerp(curveRadius, endVal.curveRadius, t);
            lerpedState.curvatureRadPerMeter = GeometryUtil.doubleLerp(curvatureRadPerMeter, endVal.curvatureRadPerMeter, t);

            return lerpedState;
        }
    }

    protected static class Waypoint {
        protected Translation2d anchorPoint;
        protected Translation2d prevControl;
        protected Translation2d nextControl;
        protected double velOverride;
        protected Rotation2d holonomicRotation;
        protected boolean isReversal;
        protected boolean isStopPoint;
        protected double waitTime;

        protected Waypoint(Translation2d anchorPoint, Translation2d prevControl, Translation2d nextControl, double velOverride, Rotation2d holonomicRotation, boolean isReversal, boolean isStopPoint, double waitTime){
            this.anchorPoint = anchorPoint;
            this.prevControl = prevControl;
            this.nextControl = nextControl;
            this.velOverride = velOverride;
            this.holonomicRotation = holonomicRotation;
            this.isReversal = isReversal;
            this.isStopPoint = isStopPoint;
            this.waitTime = waitTime;
        }
    }

    public static class EventMarker {
        public ArrayList<String> names;
        public double timeSeconds;
        public Translation2d positionMeters;
        protected double waypointRelativePos;

        protected EventMarker(ArrayList<String> names, double waypointRelativePos){
            this.names = names;
            this.waypointRelativePos = waypointRelativePos;
        }
    }
}
