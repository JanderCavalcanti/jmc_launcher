package com.openauto.dash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/*
 * JMCAR's fuel prices tile: the prices the driver typed, not a feed. The
 * original read the French government's open data (and Tankerkönig in
 * Germany), which has nothing for Brazil. Here the tile shows the price of
 * the car's fuel and, for a flex car, petrol and ethanol side by side with
 * the 70% rule (ethanol pays while it costs at most 70% of petrol) and what
 * is in the tank now. A tap, parked, opens the prices to type them; they are
 * the car profile's own ([CarProfile.fuelPrice], [CarProfile.ethanolPrice]),
 * so the trip costs and the range follow them.
 */

/** "6,29": two decimals, as pumps in Brazil show them. */
internal fun jmcPrice(price: Double): String = String.format(Locale.getDefault(), "%.2f", price)

/** The unit after the currency: litres, or cubic metres for natural gas. */
private fun perUnit(car: CarProfile): String = car.currency + if (car.fuel == FuelType.CNG) "/m³" else "/L"

/** Ethanol's price as a share of petrol's, in percent; null while either is missing. */
internal fun ethanolShare(car: CarProfile): Int? {
    if (car.fuel != FuelType.FLEX || car.ethanolPrice <= 0 || car.fuelPrice <= 0) return null
    return (car.ethanolPrice / car.fuelPrice * 100).roundToInt()
}

/** The verdict line for a flex car: which fuel pays, by the 70% rule. */
@Composable
private fun verdict(car: CarProfile): String? = ethanolShare(car)?.let { share ->
    if (share <= (ETHANOL_PAYS_RATIO * 100).roundToInt()) stringResource(R.string.jmc_fuel_ethanol_pays, share)
    else stringResource(R.string.jmc_fuel_petrol_pays, share)
}

/** The fuel the main price is for: petrol in a flex car, else the car's own fuel. */
@Composable
private fun mainFuelLabel(car: CarProfile): String =
    if (car.fuel == FuelType.FLEX) stringResource(R.string.jmc_fuel_petrol) else stringResource(car.fuel.labelRes)

/** The standard tile. */
@Composable
internal fun TypedFuelPricesCard(modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val lock = LocalDriveLock.current
    var editing by remember { mutableStateOf(false) }
    Card(modifier = modifier.clickable { lock.whenParked { editing = true } }) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TileHeader(stringResource(R.string.fuel_title)) {
                Text(perUnit(car), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            PriceLine(mainFuelLabel(car), car.fuelPrice, highlight = car.fuel != FuelType.FLEX || !car.ethanolNow)
            if (car.fuel == FuelType.FLEX) {
                if (car.ethanolPrice > 0) PriceLine(stringResource(R.string.car_fuel_ethanol), car.ethanolPrice, highlight = car.ethanolNow)
                verdict(car)?.let { line ->
                    val pays = (ethanolShare(car) ?: 100) <= (ETHANOL_PAYS_RATIO * 100).roundToInt()
                    Text(
                        line, color = if (pays) DashColors.Good else DashColors.TextSecondary, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    stringResource(R.string.jmc_fuel_in_tank, stringResource(if (car.ethanolNow) R.string.car_fuel_ethanol else R.string.jmc_fuel_petrol)),
                    color = DashColors.Muted, maxLines = 1, style = MaterialTheme.typography.labelSmall
                )
            }
            if (car.fuelPrice <= 0 || (car.fuel == FuelType.FLEX && car.ethanolPrice <= 0)) {
                Text(stringResource(R.string.jmc_fuel_tap_to_type), color = DashColors.Muted, maxLines = 2, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (editing) FuelPriceDialog(onDismiss = { editing = false })
}

/** One fuel and its price, the one in the tank now in the accent colour. */
@Composable
private fun PriceLine(label: String, price: Double, highlight: Boolean) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(
            label, color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (price > 0) jmcPrice(price) else "--",
            color = if (highlight) DashColors.AccentInk else DashColors.TextPrimary,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge
        )
    }
}

/** The same for the designed faces (gauges, signs and the like). */
@Composable
internal fun typedFuelPricesFace(): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val lock = LocalDriveLock.current
    var editing by remember { mutableStateOf(false) }
    if (editing) FuelPriceDialog(onDismiss = { editing = false })
    val now = car.priceNow
    val stats = buildList {
        add(FaceStat(mainFuelLabel(car), if (car.fuelPrice > 0) jmcPrice(car.fuelPrice) else "--"))
        if (car.fuel == FuelType.FLEX) add(FaceStat(stringResource(R.string.car_fuel_ethanol), if (car.ethanolPrice > 0) jmcPrice(car.ethanolPrice) else "--"))
    }
    return WidgetFace(
        icon = Icons.Filled.LocalGasStation,
        title = BuiltinKind.FUEL_PRICES.label,
        value = if (now > 0) jmcPrice(now) else "--",
        unit = perUnit(car),
        caption = verdict(car) ?: stringResource(R.string.jmc_fuel_tap_to_type),
        stats = stats,
        onClick = { lock.whenParked { editing = true } }
    )
}

/** The prices to type: the car's fuel, and for a flex car ethanol's and what is in the tank. */
@Composable
internal fun FuelPriceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(CarProfileStore.current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.jmc_fuel_prices_title), color = DashColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.jmc_fuel_prices_body), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                ChoiceRow(FuelType.entries, draft.fuel, { context.getString(it.labelRes) }, perRow = 4) { draft = draft.copy(fuel = it) }
                val main = if (draft.fuel == FuelType.FLEX) stringResource(R.string.jmc_fuel_petrol) else stringResource(draft.fuel.labelRes)
                NumSpec(stringResource(R.string.jmc_fuel_main_price, main), draft.fuelPrice.takeIf { it > 0 }, 0, Modifier.fillMaxWidth(), decimals = true) {
                    draft = draft.copy(fuelPrice = it ?: 0.0)
                }
                if (draft.fuel == FuelType.FLEX) {
                    NumSpec(stringResource(R.string.jmc_fuel_ethanol_price), draft.ethanolPrice.takeIf { it > 0 }, 0, Modifier.fillMaxWidth(), decimals = true) {
                        draft = draft.copy(ethanolPrice = it ?: 0.0)
                    }
                    SwitchRow(stringResource(R.string.jmc_fuel_ethanol_now), stringResource(R.string.jmc_fuel_ethanol_now_detail), draft.ethanolNow) {
                        draft = draft.copy(ethanolNow = it)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only the fuel and its prices: the rest of the profile as it is now.
                val now = CarProfileStore.current
                CarProfileStore.save(now.copy(fuel = draft.fuel, fuelPrice = draft.fuelPrice, ethanolPrice = draft.ethanolPrice, ethanolNow = draft.ethanolNow))
                onDismiss()
            }) { Text(stringResource(R.string.jmc_fuel_save), color = DashColors.AccentInk) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.jmc_cancel), color = DashColors.Muted) }
        }
    )
}
