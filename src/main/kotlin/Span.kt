// `start` is inclusive and `end` is exclusive
class Span<A>(val location: Location, val item: A)

class Location(val start: Int, val end: Int) {
    fun combine(other: Location): Location {
        return Location(minOf(start, other.start), maxOf(end, other.end))
    }

    fun length(): Int {
        return end - start
    }
}