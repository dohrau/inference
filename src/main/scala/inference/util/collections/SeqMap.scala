/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2011-2021 ETH Zurich.
 */

package inference.util.collections

object SeqMap {
  /**
   * Adds the given value to the sequence associated with the given key in the given map.
   *
   * @param map   The map.
   * @param key   The key.
   * @param value The value.
   * @tparam K The key type.
   * @tparam V The value type.
   * @return The updated map.
   */
    @inline
  def add[K, V](map: Map[K, Seq[V]], key: K, value: V): Map[K, Seq[V]] =
    map.updated(key, map.get(key).map(_ :+ value).getOrElse(Seq(value)))
}
